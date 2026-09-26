"""Issuer-data verification of an mdoc (ISO/IEC 18013-5 §9.3.1), used for self-checks and tests.

Checks: issuerAuth COSE signature with the x5chain DS certificate, DS -> IACA chain,
DS extended key usage, validity window, docType, and each IssuerSignedItem digest.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import hmac
from typing import Any

import cbor2
from cryptography import x509
from cryptography.hazmat.primitives.asymmetric import ec

from ..pki import MDL_DS_EKU
from . import cose


def _parse_tdate(v: Any) -> dt.datetime:
    s = v.value if isinstance(v, cbor2.CBORTag) else v
    if isinstance(s, dt.datetime):
        return s
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def _untag(v: Any) -> Any:
    if isinstance(v, cbor2.CBORTag):
        return _untag(v.value)
    if isinstance(v, bytes):
        return v
    if isinstance(v, dict):
        return {k: _untag(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_untag(x) for x in v]
    if isinstance(v, (dt.date, dt.datetime)):
        return v.isoformat()
    return v


def verify_issuer_signed(issuer_signed: bytes | dict, trusted_iacas: list[x509.Certificate],
                         expected_doc_type: str | None = None, now: dt.datetime | None = None) -> dict:
    now = now or dt.datetime.now(dt.timezone.utc)
    obj = cbor2.loads(issuer_signed) if isinstance(issuer_signed, bytes) else issuer_signed
    issuer_auth = obj["issuerAuth"]
    unprot = issuer_auth[1]
    x5 = unprot.get(cose.HDR_X5CHAIN)
    if x5 is None:
        raise ValueError("issuerAuth lacks x5chain")
    chain = [x5] if isinstance(x5, bytes) else list(x5)
    ds = x509.load_der_x509_certificate(chain[0])
    pub = ds.public_key()
    if not isinstance(pub, ec.EllipticCurvePublicKey):
        raise ValueError("DS key must be EC")
    cose.verify1(pub, issuer_auth)

    # Chain to a trusted IACA
    for iaca in trusted_iacas:
        if iaca.subject == ds.issuer:
            iaca.public_key().verify(ds.signature, ds.tbs_certificate_bytes, ec.ECDSA(ds.signature_hash_algorithm))
            break
    else:
        raise ValueError(f"DS issuer {ds.issuer.rfc4514_string()} not among trusted IACAs")
    eku = ds.extensions.get_extension_for_class(x509.ExtendedKeyUsage).value
    if MDL_DS_EKU not in eku:
        raise ValueError("DS certificate lacks the mdoc DS extended key usage")
    if not (ds.not_valid_before_utc <= now <= ds.not_valid_after_utc):
        raise ValueError("DS certificate not valid now")

    tagged = cbor2.loads(issuer_auth[2])
    mso = cbor2.loads(tagged.value)
    if expected_doc_type and mso["docType"] != expected_doc_type:
        raise ValueError("docType mismatch")
    vi = mso["validityInfo"]
    if not (_parse_tdate(vi["validFrom"]) <= now <= _parse_tdate(vi["validUntil"])):
        raise ValueError("MSO not valid at this time")
    if mso["digestAlgorithm"] != "SHA-256":
        raise ValueError("unexpected digest algorithm")

    claims: dict[str, dict[str, Any]] = {}
    for ns, items in obj["nameSpaces"].items():
        digests = mso["valueDigests"][ns]
        for tagged_item in items:
            item = cbor2.loads(tagged_item.value)
            expected = digests.get(item["digestID"])
            actual = hashlib.sha256(cbor2.dumps(tagged_item)).digest()
            if expected is None or not hmac.compare_digest(expected, actual):
                raise ValueError(f"digest mismatch for {ns}/{item['elementIdentifier']}")
            claims.setdefault(ns, {})[item["elementIdentifier"]] = _untag(item["elementValue"])
    return {
        "doc_type": mso["docType"],
        "valid_from": _parse_tdate(vi["validFrom"]).isoformat(),
        "valid_until": _parse_tdate(vi["validUntil"]).isoformat(),
        "device_key": mso["deviceKeyInfo"]["deviceKey"],
        "ds_subject": ds.subject.rfc4514_string(),
        "claims": claims,
    }
