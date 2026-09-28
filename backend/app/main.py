"""PID issuer backend: eMRTD / document-image verification and ISO 18013-5 PID issuance."""
from __future__ import annotations

import base64
import binascii
import datetime as dt
import hashlib
import logging
import os
from contextlib import asynccontextmanager
from typing import Any

import json

import cbor2
from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.concurrency import run_in_threadpool
from pydantic import BaseModel, Field

from . import face, portal
from .attestations import ATTESTATION_CONFIG_IDS, CREDENTIAL_TYPES
from .config import Settings, get_settings
from .docscan import pdf
from .docscan.pipeline import ScanThresholds, analyze
from .emrtd import chip_auth
from .emrtd.csca_store import CscaStore
from .emrtd.service import verify_emrtd
from .mdoc import cose
from .mdoc.issuer import build_issuer_signed
from .mdoc.verifier import verify_issuer_signed
from .oid4vci import OAuthError, Oid4vciIssuer
from .pid import PID_DOCTYPE, PID_NAMESPACE, IdentityEvidence, build_namespaces, pid_expiry
from .report import Section
from .pki import IssuerPki, load_or_create
from .sessions import SessionStore
from . import wallet_provider

log = logging.getLogger("pid-issuer")
logging.basicConfig(level=logging.INFO)


class State:
    settings: Settings
    pki: IssuerPki
    csca: CscaStore
    sessions: SessionStore
    oid4vci: Oid4vciIssuer
    wallet_provider: wallet_provider.WalletProvider


state = State()


def init_state(settings: Settings | None = None) -> None:
    s = settings or get_settings()
    state.settings = s
    state.pki = load_or_create(s.pki_dir, s.issuer_country, s.issuer_organization, s.public_base_url)
    state.csca = CscaStore.from_directory(s.csca_dir)
    state.sessions = SessionStore(s.session_ttl_seconds)
    state.wallet_provider = wallet_provider.load_or_create(s.pki_dir)
    state.oid4vci = Oid4vciIssuer(
        s.public_base_url, state.pki.ds_key, state.pki.ds_cert, issuing_authority=s.issuing_authority,
        issuing_country=state.pki.country, max_validity_days=s.pid_max_validity_days,
        display_name=s.issuing_authority, offer_ttl=s.oid4vci_offer_ttl_seconds,
        wallet_provider=state.wallet_provider)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if not hasattr(state, "pki"):
        init_state()
    yield


app = FastAPI(title="eMRTD PID Issuer", version="1.0.0", lifespan=lifespan)
app.mount("/static", StaticFiles(directory=os.path.join(os.path.dirname(__file__), "static")), name="static")


@app.exception_handler(OAuthError)
async def oauth_error(_req: Request, exc: OAuthError) -> JSONResponse:
    return JSONResponse(exc.body(), status_code=exc.status, headers={"Cache-Control": "no-store"})


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------


def b64d(value: str, what: str) -> bytes:
    v = value.strip().replace("-", "+").replace("_", "/")
    try:
        return base64.b64decode(v + "=" * (-len(v) % 4), validate=True)
    except (binascii.Error, ValueError) as e:
        raise HTTPException(400, f"{what} is not valid base64") from e


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def parse_device_key(b64: str) -> dict:
    try:
        key = cbor2.loads(b64d(b64, "device_key"))
        cose.cose_key_to_ec(key)  # validates curve and point
        return key
    except HTTPException:
        raise
    except Exception as e:  # noqa: BLE001
        raise HTTPException(400, f"device_key must be a CBOR-encoded EC2 COSE_Key: {e}") from e


def _json_safe(v: Any) -> Any:
    if isinstance(v, bytes):
        return {"bytes_b64": b64u(v), "length": len(v)} if len(v) > 64 else v.hex()
    if isinstance(v, cbor2.CBORTag):
        return _json_safe(v.value)
    if isinstance(v, dict):
        return {str(k): _json_safe(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_json_safe(x) for x in v]
    if isinstance(v, (dt.date, dt.datetime)):
        return v.isoformat()
    return v


def issue_pid(ev: IdentityEvidence, device_key: dict) -> dict:
    s = state.settings
    now = dt.datetime.now(dt.timezone.utc)
    today = now.date()
    expiry = pid_expiry(ev, today, s.pid_max_validity_days)
    namespaces = build_namespaces(ev, issuing_authority=s.issuing_authority, issuing_country=state.pki.country,
                                  issuance=today, expiry=expiry)
    valid_until = dt.datetime.combine(expiry, dt.time(23, 59, 59), dt.timezone.utc)
    issuer_signed = build_issuer_signed(PID_DOCTYPE, namespaces, device_key, state.pki.ds_key, state.pki.ds_cert,
                                        valid_from=now, valid_until=valid_until, signed=now)
    preview = {ns: {k: v for k, v in els.items() if k != "portrait"} for ns, els in namespaces.items()}
    return {
        "format": "mso_mdoc",
        "doctype": PID_DOCTYPE,
        "issuer_signed": b64u(issuer_signed),
        "valid_until": valid_until.isoformat(),
        "claims": _json_safe(preview),
        "has_portrait": "portrait" in namespaces[PID_NAMESPACE],
    }


def render_pdf_pages(front: bytes, back: bytes | None) -> tuple[bytes, bytes | None]:
    """PDF scans: page 1 = front, page 2 = back (ID cards); a one-page PDF may hold both sides."""
    try:
        if pdf.is_pdf(front):
            pages = pdf.render_pages(front, max_pages=1 if back else 2)
            front = pages[0]
            if back is None and len(pages) > 1:
                back = pages[1]
        if back and pdf.is_pdf(back):
            back = pdf.render_pages(back, max_pages=1)[0]
    except ValueError as e:
        raise HTTPException(400, f"PDF upload: {e}") from e
    return front, back


def check_selfie(evidence: IdentityEvidence | None, selfie: bytes | None, frames: dict[str, bytes],
                 device_report: dict | None) -> tuple[Section | None, list[str]]:
    """Face match + liveness of the holder's selfie against the portrait of the verified document."""
    s = state.settings
    if evidence is None:
        return None, []
    if selfie is None:
        evidence.evidence_checks["face_match"] = "not_performed"
        return None, (["a selfie with liveness check is required"] if s.require_selfie else [])
    section, summary, reasons = face.verify_selfie(s.model_dir, evidence.portrait_jpeg, selfie, frames, device_report)
    evidence.evidence_checks.update(summary)
    return section, reasons


def attestation_offer(evidence: IdentityEvidence) -> dict:
    """Offer the attestations derived from the same verified evidence (no tx code: the app already
    holds the authenticated channel)."""
    offer = state.oid4vci.create_offer(evidence, list(ATTESTATION_CONFIG_IDS), with_tx_code=False,
                                       label="in-app", ttl=600)
    return {"uri": state.oid4vci.offer_uri(offer),
            "credentials": [{"id": c, "name": CREDENTIAL_TYPES[c].name} for c in offer.config_ids]}


def _parse_report(raw: str | dict | None) -> dict | None:
    if not raw:
        return None
    if isinstance(raw, dict):
        return raw
    try:
        v = json.loads(raw)
        return v if isinstance(v, dict) else None
    except json.JSONDecodeError:
        return None


# ---------------------------------------------------------------------------
# PKI / meta endpoints
# ---------------------------------------------------------------------------


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "csca_certificates": len(state.csca), "issuer_country": state.pki.country,
            # set by Render for each deploy; lets clients see which build is live
            "face_models": face.engine(state.settings.model_dir).available(),
            "version": os.environ.get("RENDER_GIT_COMMIT", "local")[:7]}


@app.get("/pki/iaca.pem")
def iaca_pem() -> Response:
    return Response(state.pki.iaca_pem(), media_type="application/x-pem-file")


@app.get("/pki/iaca.der")
def iaca_der() -> Response:
    from cryptography.hazmat.primitives import serialization

    return Response(state.pki.iaca_cert.public_bytes(serialization.Encoding.DER), media_type="application/pkix-cert")


@app.get("/pki/ds.pem")
def ds_pem() -> Response:
    return Response(state.pki.ds_pem(), media_type="application/x-pem-file")


@app.get("/pki/crl.der")
def crl() -> Response:
    return Response(state.pki.crl_der(), media_type="application/pkix-crl")


# ---------------------------------------------------------------------------
# eMRTD flow
# ---------------------------------------------------------------------------


class ChallengeRequest(BaseModel):
    dg14: str | None = Field(None, description="base64 EF.DG14, if present on the chip")
    ca_key_id: int | None = None


class ChallengeResponse(BaseModel):
    session_id: str
    aa_challenge: str = Field(description="8-byte hex challenge for INTERNAL AUTHENTICATE")
    chip_authentication: dict | None = None
    chip_authentication_error: str | None = None
    expires_in: int


@app.post("/api/v1/emrtd/challenge", response_model=ChallengeResponse)
def emrtd_challenge(req: ChallengeRequest) -> ChallengeResponse:
    sess = state.sessions.create()
    ca = None
    if req.dg14:
        dg14 = b64d(req.dg14, "dg14")
        sess.dg14_hash = hashlib.sha256(dg14).digest()
        try:
            sess.ca = chip_auth.prepare(dg14, key_id=req.ca_key_id)
            ca = sess.ca.public_dict()
        except Exception as e:  # noqa: BLE001
            sess.ca_error = str(e)
    return ChallengeResponse(session_id=sess.id, aa_challenge=sess.aa_challenge.hex(), chip_authentication=ca,
                             chip_authentication_error=sess.ca_error, expires_in=state.settings.session_ttl_seconds)


class EmrtdIssueRequest(BaseModel):
    session_id: str
    sod: str
    data_groups: dict[str, str] = Field(description='{"1": base64, "2": base64, ...}')
    active_auth_signature: str | None = None
    chip_auth_response: str | None = Field(None, description="raw chip response (incl. SW) to the CA command")
    access_control: str | None = Field(None, description="BAC, PACE, PACE-CAM ...")
    device_key: str = Field(description="base64 CBOR COSE_Key of the wallet's device key")
    selfie: str | None = Field(None, description="base64 JPEG selfie (frontal) for the face match")
    liveness_frames: dict[str, str] | None = Field(None, description='{"turn_left": b64 JPEG, "turn_right": ...}')
    liveness_report: dict | None = None


@app.post("/api/v1/emrtd/issue")
def emrtd_issue(req: EmrtdIssueRequest) -> dict:
    sess = state.sessions.consume(req.session_id)
    if sess is None:
        raise HTTPException(404, "unknown or expired session; request a new challenge")
    device_key = parse_device_key(req.device_key)
    dgs: dict[int, bytes] = {}
    for k, v in req.data_groups.items():
        try:
            num = int(k.lower().removeprefix("dg"))
        except ValueError as e:
            raise HTTPException(400, f"invalid data group key {k}") from e
        dgs[num] = b64d(v, f"DG{num}")
    s = state.settings
    result = verify_emrtd(
        sess, b64d(req.sod, "sod"), dgs,
        b64d(req.active_auth_signature, "active_auth_signature") if req.active_auth_signature else None,
        b64d(req.chip_auth_response, "chip_auth_response") if req.chip_auth_response else None,
        req.access_control, state.csca, s.require_csca_trust, s.require_chip_genuineness,
    )
    decision, reasons, sections = result.decision, list(result.reasons), list(result.sections)
    if decision == "accepted":
        frames = {k: b64d(v, k) for k, v in (req.liveness_frames or {}).items()}
        bio, bio_reasons = check_selfie(result.evidence, b64d(req.selfie, "selfie") if req.selfie else None,
                                        frames, req.liveness_report)
        if bio is not None:
            sections.append(bio)
        if bio_reasons:
            decision, reasons = "rejected", reasons + bio_reasons
    body: dict[str, Any] = {
        "decision": decision,
        "reasons": reasons,
        "mrz": result.mrz,
        "report": [sec.to_dict() for sec in sections],
        "summary": result.facts,
    }
    if decision == "accepted" and result.evidence:
        body["credential"] = issue_pid(result.evidence, device_key)
        body["attestation_offer"] = attestation_offer(result.evidence)
    return _json_safe(body)


# ---------------------------------------------------------------------------
# Document image flow
# ---------------------------------------------------------------------------


@app.post("/api/v1/document/issue")
async def document_issue(
    front: UploadFile = File(..., description="photo or PDF of the data page / card front (a 2-page PDF may hold front+back)"),
    back: UploadFile | None = File(None, description="photo of the card back (TD1 ID cards carry the MRZ there)"),
    device_key: str = Form(...),
    device_ocr_text: str = Form("", description="text recognised on-device (ML Kit), all sides"),
    document_kind: str | None = Form(None, description="passport | id_card"),
    image_source: str = Form("camera", description="camera (captured live in the app) | upload (picked from files)"),
    selfie: UploadFile | None = File(None, description="frontal selfie (JPEG) for the face match"),
    liveness_left: UploadFile | None = File(None, description="selfie frame with the head turned left"),
    liveness_right: UploadFile | None = File(None, description="selfie frame with the head turned right"),
    liveness_report: str = Form("", description="JSON report of the in-app liveness challenges"),
) -> dict:
    key = parse_device_key(device_key)
    if image_source not in ("camera", "upload"):
        raise HTTPException(400, "image_source must be 'camera' or 'upload'")
    s = state.settings
    front_bytes = await front.read()
    back_bytes = await back.read() if back is not None else None
    if len(front_bytes) > 25_000_000 or (back_bytes and len(back_bytes) > 25_000_000):
        raise HTTPException(413, "file too large")
    front_bytes, back_bytes = render_pdf_pages(front_bytes, back_bytes)
    if front.content_type == "application/pdf" or (back is not None and back.content_type == "application/pdf"):
        image_source = "upload"
    try:
        # CPU-heavy: run in a worker thread so the event loop keeps answering health checks
        # (a blocked loop makes the host restart the service mid-request -> HTTP 502)
        result = await run_in_threadpool(
            analyze, front_bytes, back_bytes or None, device_ocr_text, document_kind,
            ScanThresholds(min_score=s.scan_min_score, allow_specimen=s.scan_allow_specimen),
            image_source=image_source)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    decision, reasons, sections = result.decision, list(result.reasons), list(result.sections)
    if decision == "accepted":
        selfie_bytes = await selfie.read() if selfie is not None else None
        frames = {k: await f.read() for k, f in (("turn_left", liveness_left), ("turn_right", liveness_right))
                  if f is not None}
        bio, bio_reasons = await run_in_threadpool(check_selfie, result.evidence, selfie_bytes or None, frames,
                                                   _parse_report(liveness_report))
        if bio is not None:
            sections.append(bio)
        if bio_reasons:
            decision, reasons = "rejected", reasons + bio_reasons
    body: dict[str, Any] = {
        "decision": decision,
        "reasons": reasons,
        "score": round(result.score, 3),
        "mrz": result.mrz.to_dict() if result.mrz else None,
        "report": [sec.to_dict() for sec in sections],
    }
    if decision == "accepted" and result.evidence:
        body["credential"] = issue_pid(result.evidence, key)
        body["attestation_offer"] = attestation_offer(result.evidence)
    return _json_safe(body)


# ---------------------------------------------------------------------------
# Self-check
# ---------------------------------------------------------------------------


class VerifyRequest(BaseModel):
    issuer_signed: str


@app.post("/api/v1/mdoc/verify")
def mdoc_verify(req: VerifyRequest) -> dict:
    try:
        out = verify_issuer_signed(b64d(req.issuer_signed, "issuer_signed"), [state.pki.iaca_cert])
    except Exception as e:  # noqa: BLE001
        return {"valid": False, "error": str(e)}
    return _json_safe({"valid": True, **out})


# ---------------------------------------------------------------------------
# OpenID4VCI issuer (pre-authorized code flow)
# ---------------------------------------------------------------------------

_NO_STORE = {"Cache-Control": "no-store"}


@app.get("/.well-known/openid-credential-issuer")
def credential_issuer_metadata() -> dict:
    return state.oid4vci.credential_issuer_metadata()


@app.get("/.well-known/oauth-authorization-server")
@app.get("/.well-known/openid-configuration")
def authorization_server_metadata() -> dict:
    return state.oid4vci.authorization_server_metadata()


@app.get("/oid4vci/offers/{offer_id}")
def credential_offer(offer_id: str) -> JSONResponse:
    offer = state.oid4vci.get_offer(offer_id)
    if offer is None:
        raise HTTPException(404, "credential offer unknown or expired")
    return JSONResponse(state.oid4vci.offer_document(offer), headers=_NO_STORE)


@app.get("/oid4vci/offers/{offer_id}/status")
def credential_offer_status(offer_id: str) -> dict:
    offer = state.oid4vci.get_offer(offer_id)
    return {"status": offer.status if offer else "expired", "issued": offer.issued if offer else []}


@app.post("/oid4vci/token")
async def oid4vci_token(request: Request) -> JSONResponse:
    form = await request.form()
    return JSONResponse(state.oid4vci.token(
        {k: str(v) for k, v in form.items()},
        client_attestation=request.headers.get("OAuth-Client-Attestation"),
        client_attestation_pop=request.headers.get("OAuth-Client-Attestation-PoP"),
    ), headers=_NO_STORE)


@app.post("/oid4vci/nonce")
def oid4vci_nonce() -> JSONResponse:
    return JSONResponse(state.oid4vci.nonce(), headers=_NO_STORE)


@app.post("/oid4vci/credential")
async def oid4vci_credential(request: Request) -> JSONResponse:
    try:
        body = await request.json()
    except (json.JSONDecodeError, UnicodeDecodeError) as e:
        raise OAuthError(400, "invalid_credential_request", "body must be JSON") from e
    if not isinstance(body, dict):
        raise OAuthError(400, "invalid_credential_request", "body must be a JSON object")
    out = await run_in_threadpool(state.oid4vci.credential, request.headers.get("authorization"), body)
    return JSONResponse(out, headers=_NO_STORE)


class OfferSubject(BaseModel):
    family_name: str
    given_name: str
    birth_date: dt.date
    sex: str = "X"
    nationality: str
    document_number: str
    document_expiry: dt.date | None = None
    portrait: str | None = Field(None, description="base64 JPEG/PNG with one face")


class OfferRequest(BaseModel):
    credentials: list[str] = Field(default_factory=lambda: list(CREDENTIAL_TYPES))
    tx_code: bool = True
    subject: OfferSubject


def manual_evidence(sub: OfferSubject, portrait: bytes | None) -> IdentityEvidence:
    if not state.settings.oid4vci_allow_manual_entry:
        raise HTTPException(403, "manual entry is disabled on this issuer")
    nat = sub.nationality.strip().upper()
    if not (len(nat) == 3 and nat.isalpha()):
        raise HTTPException(400, "nationality must be an ISO 3166-1 alpha-3 code")
    jpeg = None
    if portrait:
        jpeg = face.portrait_jpeg(state.settings.model_dir, portrait)
        if jpeg is None:
            raise HTTPException(400, "the portrait must show exactly one face")
    return IdentityEvidence(
        family_name=sub.family_name.strip().upper(), given_name=sub.given_name.strip().upper(),
        birth_date=sub.birth_date, sex=(sub.sex or "X")[:1].upper(), nationality=nat, issuing_state=nat,
        document_number=sub.document_number.strip().upper(), document_type="MANUAL",
        document_expiry=sub.document_expiry, portrait_jpeg=jpeg, evidence_type="manual_entry_unverified",
        evidence_checks={"verified": False})


@app.post("/api/v1/oid4vci/offers")
def create_offer_api(req: OfferRequest) -> dict:
    ev = manual_evidence(req.subject, b64d(req.subject.portrait, "portrait") if req.subject.portrait else None)
    try:
        offer = state.oid4vci.create_offer(ev, req.credentials, with_tx_code=req.tx_code, label="api")
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    return {"offer_id": offer.id, "credential_offer_uri": state.oid4vci.offer_uri(offer), "tx_code": offer.tx_code,
            "expires_in": state.settings.oid4vci_offer_ttl_seconds}


# --- web portal -------------------------------------------------------------


@app.get("/", include_in_schema=False)
def root() -> RedirectResponse:
    return RedirectResponse("/issuer")


@app.get("/issuer", response_class=HTMLResponse, include_in_schema=False)
def issuer_portal(mode: str = "document") -> str:
    return portal.index_page(state.settings.oid4vci_allow_manual_entry, mode)


@app.post("/issuer/offer", response_class=HTMLResponse, include_in_schema=False)
async def issuer_portal_offer(request: Request) -> HTMLResponse:
    form = await request.form()
    config_ids = [str(c) for c in form.getlist("credentials")]
    with_tx = form.get("tx_code") == "1"
    report: list[str] = []
    try:
        if form.get("mode") == "manual":
            portrait_file = form.get("portrait")
            portrait = await portrait_file.read() if hasattr(portrait_file, "read") else None
            sub = OfferSubject(
                family_name=str(form.get("family_name", "")), given_name=str(form.get("given_name", "")),
                birth_date=dt.date.fromisoformat(str(form.get("birth_date"))), sex=str(form.get("sex", "X")),
                nationality=str(form.get("nationality", "")), document_number=str(form.get("document_number", "")),
                document_expiry=dt.date.fromisoformat(str(form["document_expiry"]))
                if form.get("document_expiry") else None)
            ev = manual_evidence(sub, portrait or None)
            report.append("Self-asserted test data: nothing was verified")
        else:
            front_f, back_f = form.get("front"), form.get("back")
            if not hasattr(front_f, "read"):
                raise HTTPException(400, "upload the document front / data page")
            front = await front_f.read()
            back = (await back_f.read()) if hasattr(back_f, "read") else None
            if len(front) > 25_000_000 or (back and len(back) > 25_000_000):
                raise HTTPException(413, "file too large")
            front, back = render_pdf_pages(front, back or None)
            s = state.settings
            kind = str(form.get("document_kind") or "") or None
            result = await run_in_threadpool(
                analyze, front, back, "", kind, ScanThresholds(min_score=s.scan_min_score,
                                                              allow_specimen=s.scan_allow_specimen),
                image_source="upload")
            report = [f"{sec.name}: {c.name} — {c.status.value}{': ' + c.detail if c.detail else ''}"
                      for sec in result.sections for c in sec.checks]
            if result.decision != "accepted" or result.evidence is None:
                return HTMLResponse(portal.error_page("The document was not accepted: " + "; ".join(result.reasons),
                                                      report), status_code=422)
            ev = result.evidence
        offer = state.oid4vci.create_offer(ev, config_ids, with_tx_code=with_tx, label="portal")
    except HTTPException as e:
        return HTMLResponse(portal.error_page(str(e.detail)), status_code=e.status_code)
    except ValueError as e:
        return HTMLResponse(portal.error_page(str(e)), status_code=400)
    names = [CREDENTIAL_TYPES[c].name for c in offer.config_ids]
    holder = f"{ev.given_name} {ev.family_name}".strip()
    return HTMLResponse(portal.offer_page(offer.id, state.oid4vci.offer_uri(offer), offer.tx_code, names, holder,
                                          report))


# ---------------------------------------------------------------------------
# TEST Wallet Provider (key attestations for the wallet's device keys)
# ---------------------------------------------------------------------------


class KeyAttestationRequest(BaseModel):
    keys: list[dict] = Field(description="public JWKs (EC P-256) of the device keys to attest")
    nonce: str | None = Field(None, description="issuer c_nonce to bind, when the wallet has one")
    android_attestation: list[list[str]] | None = Field(
        None, description="per key, the Android Keystore attestation certificate chain (base64 DER)")
    wallet_unit_id: str | None = None


@app.post("/wallet-provider/key-attestation")
def key_attestation(req: KeyAttestationRequest) -> dict:
    try:
        jwt = state.wallet_provider.key_attestation(req.keys, req.nonce)
    except (ValueError, KeyError) as e:
        raise HTTPException(400, f"cannot attest keys: {e}") from e
    return {"key_attestation": jwt}


class WalletAttestationRequest(BaseModel):
    jwk: dict = Field(description="public JWK (EC P-256) of the wallet's client-attestation PoP key")
    client_id: str = Field(description="the OAuth client_id the wallet uses with issuers")
    wallet_unit_id: str | None = None


@app.post("/wallet-provider/wallet-attestation")
def wallet_attestation(req: WalletAttestationRequest) -> dict:
    try:
        jwt = state.wallet_provider.wallet_attestation(
            req.jwk, req.client_id, f"{get_settings().public_base_url}/wallet-provider")
    except (ValueError, KeyError) as e:
        raise HTTPException(400, f"cannot attest wallet: {e}") from e
    return {"wallet_attestation": jwt}


@app.get("/wallet-provider/certificate.pem", include_in_schema=False)
def wallet_provider_cert() -> Response:
    from cryptography.hazmat.primitives import serialization

    return Response(state.wallet_provider.cert.public_bytes(serialization.Encoding.PEM), media_type="application/x-pem-file")
