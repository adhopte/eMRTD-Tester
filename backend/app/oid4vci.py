"""OpenID for Verifiable Credential Issuance 1.0: pre-authorized code flow, mso_mdoc credentials.

Flow (all state in memory, like the eMRTD sessions):

1. An offer is created for verified identity evidence (web portal, API, or automatically after
   an in-app PID issuance) and handed to the wallet as
   ``openid-credential-offer://?credential_offer_uri=...`` (usually a QR code).
2. The wallet fetches the offer and the issuer / authorization server metadata, then redeems
   the pre-authorized code (plus the transaction code, when the offer requires one) at the
   token endpoint.
3. It gets a ``c_nonce`` from the nonce endpoint and calls the credential endpoint with a JWT
   proof of possession (``openid4vci-proof+jwt``). The proof's ``jwk`` becomes the mdoc device
   key bound into the MSO.

DPoP and credential response encryption are not offered; wallets fall back to Bearer tokens and
plain responses (EUDI wallet-core: set the encryption policy to SUPPORTED).
"""
from __future__ import annotations

import base64
import binascii
import datetime as dt
import hmac
import json
import secrets
import threading
import time
from dataclasses import dataclass, field
from urllib.parse import quote

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

from .attestations import CREDENTIAL_TYPES, IssueContext
from .mdoc.issuer import build_issuer_signed
from .pid import IdentityEvidence
from .wallet_provider import WalletProvider, jwk_to_key

PRE_AUTH_GRANT = "urn:ietf:params:oauth:grant-type:pre-authorized_code"
PROOF_TYP = "openid4vci-proof+jwt"
TOKEN_TTL = 600
NONCE_TTL = 300
MAX_TX_ATTEMPTS = 5
PROOF_MAX_AGE = 600


class OAuthError(Exception):
    def __init__(self, status: int, error: str, description: str) -> None:
        super().__init__(description)
        self.status, self.error, self.description = status, error, description

    def body(self) -> dict:
        return {"error": self.error, "error_description": self.description}


@dataclass
class Offer:
    id: str
    pre_authorized_code: str
    config_ids: list[str]
    evidence: IdentityEvidence
    expires: float
    tx_code: str | None = None
    label: str = ""
    tx_attempts: int = 0
    status: str = "offered"  # offered -> token_issued -> issued
    issued: list[str] = field(default_factory=list)


@dataclass
class AccessToken:
    offer_id: str
    expires: float


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def b64u_dec(s: str) -> bytes:
    try:
        return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
    except (binascii.Error, ValueError) as e:
        raise ValueError("invalid base64url") from e


class Oid4vciIssuer:
    def __init__(self, issuer_id: str, ds_key, ds_cert, *, issuing_authority: str, issuing_country: str,
                 max_validity_days: int, display_name: str, offer_ttl: int = 1800,
                 wallet_provider: WalletProvider | None = None) -> None:
        self.wallet_provider = wallet_provider
        self.issuer_id = issuer_id.rstrip("/")
        self.ds_key, self.ds_cert = ds_key, ds_cert
        self.issuing_authority, self.issuing_country = issuing_authority, issuing_country
        self.max_validity_days = max_validity_days
        self.display_name = display_name
        self.offer_ttl = offer_ttl
        self._offers: dict[str, Offer] = {}
        self._by_code: dict[str, str] = {}
        self._tokens: dict[str, AccessToken] = {}
        self._nonces: dict[str, float] = {}
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ metadata

    def credential_issuer_metadata(self) -> dict:
        configs = {}
        for t in CREDENTIAL_TYPES.values():
            configs[t.config_id] = {
                "format": "mso_mdoc",
                "doctype": t.doctype,
                "scope": t.config_id,
                "cryptographic_binding_methods_supported": ["cose_key"],
                "credential_signing_alg_values_supported": [-7],
                "proof_types_supported": {"jwt": {"proof_signing_alg_values_supported": ["ES256"]}},
                "credential_metadata": {
                    "display": [{
                        "name": t.name, "locale": "en", "description": t.description,
                        "background_color": t.background_color, "text_color": t.text_color,
                        "logo": {"uri": f"{self.issuer_id}/static/emblem.svg", "alt_text": "IN Groupe"},
                    }],
                    "claims": [{"path": [ns, el], "display": [{"name": disp, "locale": "en"}]}
                               for ns, el, disp in t.claims],
                },
            }
        return {
            "credential_issuer": self.issuer_id,
            "credential_endpoint": f"{self.issuer_id}/oid4vci/credential",
            "nonce_endpoint": f"{self.issuer_id}/oid4vci/nonce",
            "display": [{"name": self.display_name, "locale": "en",
                         "logo": {"uri": f"{self.issuer_id}/static/logo.svg", "alt_text": "IN Groupe"}}],
            "credential_configurations_supported": configs,
        }

    def authorization_server_metadata(self) -> dict:
        return {
            "issuer": self.issuer_id,
            "token_endpoint": f"{self.issuer_id}/oid4vci/token",
            "response_types_supported": ["code"],
            "grant_types_supported": [PRE_AUTH_GRANT],
            "token_endpoint_auth_methods_supported": ["none"],
            "pre-authorized_grant_anonymous_access_supported": True,
        }

    # ------------------------------------------------------------------ offers

    def _purge(self) -> None:
        now = time.time()
        for k in [k for k, o in self._offers.items() if o.expires < now]:
            self._by_code.pop(self._offers.pop(k).pre_authorized_code, None)
        for k in [k for k, t in self._tokens.items() if t.expires < now]:
            del self._tokens[k]
        for k in [k for k, e in self._nonces.items() if e < now]:
            del self._nonces[k]

    def create_offer(self, evidence: IdentityEvidence, config_ids: list[str], *, with_tx_code: bool,
                     label: str = "", ttl: int | None = None) -> Offer:
        unknown = [c for c in config_ids if c not in CREDENTIAL_TYPES]
        if unknown or not config_ids:
            raise ValueError(f"unknown credential configuration(s): {unknown or 'none selected'}")
        offer = Offer(
            id=secrets.token_urlsafe(18), pre_authorized_code=secrets.token_urlsafe(32),
            config_ids=list(dict.fromkeys(config_ids)), evidence=evidence,
            expires=time.time() + (ttl or self.offer_ttl),
            tx_code=f"{secrets.randbelow(10**6):06d}" if with_tx_code else None, label=label)
        with self._lock:
            self._purge()
            self._offers[offer.id] = offer
            self._by_code[offer.pre_authorized_code] = offer.id
        return offer

    def get_offer(self, offer_id: str) -> Offer | None:
        with self._lock:
            self._purge()
            return self._offers.get(offer_id)

    def offer_document(self, offer: Offer) -> dict:
        grant: dict = {"pre-authorized_code": offer.pre_authorized_code}
        if offer.tx_code:
            grant["tx_code"] = {"input_mode": "numeric", "length": len(offer.tx_code),
                                "description": "Enter the 6-digit code shown by the issuer"}
        return {"credential_issuer": self.issuer_id, "credential_configuration_ids": offer.config_ids,
                "grants": {PRE_AUTH_GRANT: grant}}

    def offer_uri(self, offer: Offer) -> str:
        return ("openid-credential-offer://?credential_offer_uri="
                + quote(f"{self.issuer_id}/oid4vci/offers/{offer.id}", safe=""))

    # ------------------------------------------------------------------ token + nonce

    def token(self, form: dict[str, str]) -> dict:
        if form.get("grant_type") != PRE_AUTH_GRANT:
            raise OAuthError(400, "unsupported_grant_type", "only the pre-authorized code grant is supported")
        code = form.get("pre-authorized_code") or ""
        with self._lock:
            self._purge()
            offer = self._offers.get(self._by_code.get(code, ""))
            if offer is None or offer.status != "offered":
                raise OAuthError(400, "invalid_grant", "pre-authorized code is unknown, expired or already used")
            if offer.tx_code:
                given = form.get("tx_code") or ""
                if not hmac.compare_digest(given.encode(), offer.tx_code.encode()):
                    offer.tx_attempts += 1
                    if offer.tx_attempts >= MAX_TX_ATTEMPTS:
                        self._by_code.pop(code, None)
                        offer.status = "blocked"
                        raise OAuthError(400, "invalid_grant", "too many wrong transaction codes; offer revoked")
                    raise OAuthError(400, "invalid_grant", "wrong transaction code")
            offer.status = "token_issued"
            self._by_code.pop(code, None)
            token = secrets.token_urlsafe(32)
            self._tokens[token] = AccessToken(offer.id, time.time() + TOKEN_TTL)
        return {"access_token": token, "token_type": "Bearer", "expires_in": TOKEN_TTL}

    def nonce(self) -> dict:
        n = secrets.token_urlsafe(24)
        with self._lock:
            self._purge()
            self._nonces[n] = time.time() + NONCE_TTL
        return {"c_nonce": n}

    # ------------------------------------------------------------------ credential

    def _check_proof(self, jwt: str) -> list[dict]:
        """Verify a JWT key proof; return the holder key(s) to bind, as COSE_Key maps.

        Two forms are accepted: a ``jwk`` header (the proof key is the holder key) or a
        ``key_attestation`` header from the trusted Wallet Provider (OpenID4VCI 1.0 Appendix D),
        where the proof is signed by one of the attested keys and every attested key gets a
        credential.
        """
        try:
            h_b64, p_b64, s_b64 = jwt.split(".")
            header, payload = json.loads(b64u_dec(h_b64)), json.loads(b64u_dec(p_b64))
            sig = b64u_dec(s_b64)
        except (ValueError, json.JSONDecodeError, AttributeError) as e:
            raise OAuthError(400, "invalid_proof", "proof is not a compact JWS") from e
        if header.get("typ") != PROOF_TYP:
            raise OAuthError(400, "invalid_proof", f"proof typ must be {PROOF_TYP}")
        if header.get("alg") != "ES256":
            raise OAuthError(400, "invalid_proof", "proof alg must be ES256")
        if "key_attestation" in header:
            if self.wallet_provider is None:
                raise OAuthError(400, "invalid_proof", "key attestations are not accepted by this issuer")
            try:
                att = self.wallet_provider.verify_key_attestation(header["key_attestation"])
            except (ValueError, KeyError, json.JSONDecodeError) as e:
                raise OAuthError(400, "invalid_proof", f"key attestation rejected: {e}") from e
            jwks = att["attested_keys"]
            try:
                idx = int(header.get("kid", 0))
                signing_jwk = jwks[idx]
            except (ValueError, IndexError) as e:
                raise OAuthError(400, "invalid_proof", "proof kid does not reference an attested key") from e
        else:
            signing_jwk = header.get("jwk")
            if not isinstance(signing_jwk, dict):
                raise OAuthError(400, "invalid_proof", "proof needs a jwk or key_attestation header (kid/x5c not supported)")
            jwks = [signing_jwk]
        try:
            pub = jwk_to_key(signing_jwk)
            holder_keys = [jwk_to_key(j) for j in jwks]
        except (KeyError, ValueError, TypeError) as e:
            raise OAuthError(400, "invalid_proof", f"invalid key: {e}") from e
        if len(sig) != 64:
            raise OAuthError(400, "invalid_proof", "ES256 signature must be 64 bytes")
        der = encode_dss_signature(int.from_bytes(sig[:32], "big"), int.from_bytes(sig[32:], "big"))
        try:
            pub.verify(der, f"{h_b64}.{p_b64}".encode(), ec.ECDSA(hashes.SHA256()))
        except InvalidSignature as e:
            raise OAuthError(400, "invalid_proof", "proof signature does not verify") from e
        aud = payload.get("aud")
        if (aud if isinstance(aud, list) else [aud]).count(self.issuer_id) == 0:
            raise OAuthError(400, "invalid_proof", f"proof aud must be {self.issuer_id}")
        iat = payload.get("iat")
        if not isinstance(iat, (int, float)) or abs(time.time() - iat) > PROOF_MAX_AGE:
            raise OAuthError(400, "invalid_proof", "proof iat missing or too far from the current time")
        with self._lock:
            self._purge()
            if payload.get("nonce") not in self._nonces:
                raise OAuthError(400, "invalid_nonce", "proof nonce is missing, unknown or expired")
        out = []
        for k in holder_keys:
            n = k.public_numbers()
            out.append({1: 2, -1: 1, -2: n.x.to_bytes(32, "big"), -3: n.y.to_bytes(32, "big")})
        return out

    def credential(self, authorization: str | None, body: dict) -> dict:
        scheme, _, token = (authorization or "").partition(" ")
        with self._lock:
            self._purge()
            at = self._tokens.get(token) if scheme.lower() in ("bearer", "dpop") else None
            offer = self._offers.get(at.offer_id) if at else None
        if offer is None:
            raise OAuthError(401, "invalid_token", "missing, unknown or expired access token")
        config_id = body.get("credential_configuration_id")
        if config_id is None and body.get("credential_identifier"):
            config_id = body["credential_identifier"]
        if config_id not in offer.config_ids:
            raise OAuthError(400, "invalid_credential_request",
                             f"credential_configuration_id must be one of {offer.config_ids}")
        if config_id in offer.issued:
            raise OAuthError(400, "invalid_credential_request", f"{config_id} was already issued for this offer")
        proofs = body.get("proofs") or {}
        jwts = proofs.get("jwt") if isinstance(proofs, dict) else None
        if not jwts and isinstance(body.get("proof"), dict) and body["proof"].get("proof_type") == "jwt":
            jwts = [body["proof"].get("jwt")]  # pre-1.0 wallets
        if not isinstance(jwts, list) or not jwts:
            raise OAuthError(400, "invalid_proof", "a jwt proof is required (proofs.jwt)")
        if len(jwts) > 10:
            raise OAuthError(400, "invalid_proof", "at most 10 proofs per request")
        keys: list[dict] = []
        for j in jwts:
            for k in self._check_proof(j):
                if k not in keys:
                    keys.append(k)

        ctype = CREDENTIAL_TYPES[config_id]
        now = dt.datetime.now(dt.timezone.utc)
        ctx = IssueContext(self.issuing_authority, self.issuing_country, now.date(), self.max_validity_days)
        namespaces, expiry = ctype.build(offer.evidence, ctx)
        valid_until = dt.datetime.combine(expiry, dt.time(23, 59, 59), dt.timezone.utc)
        creds = [{"credential": b64u(build_issuer_signed(ctype.doctype, namespaces, k, self.ds_key, self.ds_cert,
                                                          valid_from=now, valid_until=valid_until, signed=now))}
                 for k in keys]
        with self._lock:
            offer.issued.append(config_id)
            if set(offer.issued) >= set(offer.config_ids):
                offer.status = "issued"
        return {"credentials": creds}
