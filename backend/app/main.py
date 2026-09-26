"""PID issuer backend: eMRTD / document-image verification and ISO 18013-5 PID issuance."""
from __future__ import annotations

import base64
import binascii
import datetime as dt
import hashlib
import logging
from contextlib import asynccontextmanager
from typing import Any

import cbor2
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, Field

from .config import Settings, get_settings
from .docscan import pdf
from .docscan.pipeline import ScanThresholds, analyze
from .emrtd import chip_auth
from .emrtd.csca_store import CscaStore
from .emrtd.service import verify_emrtd
from .mdoc import cose
from .mdoc.issuer import build_issuer_signed
from .mdoc.verifier import verify_issuer_signed
from .pid import PID_DOCTYPE, PID_NAMESPACE, IdentityEvidence, build_namespaces, pid_expiry
from .pki import IssuerPki, load_or_create
from .sessions import SessionStore

log = logging.getLogger("pid-issuer")
logging.basicConfig(level=logging.INFO)


class State:
    settings: Settings
    pki: IssuerPki
    csca: CscaStore
    sessions: SessionStore


state = State()


def init_state(settings: Settings | None = None) -> None:
    s = settings or get_settings()
    state.settings = s
    state.pki = load_or_create(s.pki_dir, s.issuer_country, s.issuer_organization, s.public_base_url)
    state.csca = CscaStore.from_directory(s.csca_dir)
    state.sessions = SessionStore(s.session_ttl_seconds)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    if not hasattr(state, "pki"):
        init_state()
    yield


app = FastAPI(title="eMRTD PID Issuer", version="1.0.0", lifespan=lifespan)


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


# ---------------------------------------------------------------------------
# PKI / meta endpoints
# ---------------------------------------------------------------------------


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "csca_certificates": len(state.csca), "issuer_country": state.pki.country}


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
    body: dict[str, Any] = {
        "decision": result.decision,
        "reasons": result.reasons,
        "mrz": result.mrz,
        "report": [sec.to_dict() for sec in result.sections],
        "summary": result.facts,
    }
    if result.decision == "accepted" and result.evidence:
        body["credential"] = issue_pid(result.evidence, device_key)
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
) -> dict:
    key = parse_device_key(device_key)
    if image_source not in ("camera", "upload"):
        raise HTTPException(400, "image_source must be 'camera' or 'upload'")
    s = state.settings
    front_bytes = await front.read()
    back_bytes = await back.read() if back is not None else None
    if len(front_bytes) > 25_000_000 or (back_bytes and len(back_bytes) > 25_000_000):
        raise HTTPException(413, "file too large")
    # PDF scans: page 1 = front, page 2 = back (ID cards); a one-page PDF may hold both sides
    try:
        if pdf.is_pdf(front_bytes):
            pages = pdf.render_pages(front_bytes, max_pages=1 if back_bytes else 2)
            front_bytes = pages[0]
            if back_bytes is None and len(pages) > 1:
                back_bytes = pages[1]
        if back_bytes and pdf.is_pdf(back_bytes):
            back_bytes = pdf.render_pages(back_bytes, max_pages=1)[0]
    except ValueError as e:
        raise HTTPException(400, f"PDF upload: {e}") from e
    if front.content_type == "application/pdf" or (back is not None and back.content_type == "application/pdf"):
        image_source = "upload"
    try:
        result = analyze(front_bytes, back_bytes or None, device_ocr_text, document_kind,
                         ScanThresholds(min_score=s.scan_min_score, allow_specimen=s.scan_allow_specimen),
                         image_source=image_source)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e
    body: dict[str, Any] = {
        "decision": result.decision,
        "reasons": result.reasons,
        "score": round(result.score, 3),
        "mrz": result.mrz.to_dict() if result.mrz else None,
        "report": [sec.to_dict() for sec in result.sections],
    }
    if result.decision == "accepted" and result.evidence:
        body["credential"] = issue_pid(result.evidence, key)
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
