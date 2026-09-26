"""ISO/IEC 18013-5 IssuerSigned construction (IssuerSignedItems + MSO + COSE_Sign1 issuerAuth)."""
from __future__ import annotations

import datetime as dt
import hashlib
import secrets
from typing import Any

import cbor2
from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from . import cose


def tdate(t: dt.datetime) -> cbor2.CBORTag:
    t = t.astimezone(dt.timezone.utc).replace(microsecond=0)
    return cbor2.CBORTag(0, t.strftime("%Y-%m-%dT%H:%M:%SZ"))


def full_date(d: dt.date) -> cbor2.CBORTag:
    return cbor2.CBORTag(1004, d.isoformat())


def _encode_item(digest_id: int, identifier: str, value: Any) -> bytes:
    item = {
        "digestID": digest_id,
        "random": secrets.token_bytes(16),
        "elementIdentifier": identifier,
        "elementValue": value,
    }
    return cbor2.dumps(item)


def build_issuer_signed(
    doc_type: str,
    namespaces: dict[str, dict[str, Any]],
    device_key: dict,
    ds_key: ec.EllipticCurvePrivateKey,
    ds_cert: x509.Certificate,
    valid_from: dt.datetime,
    valid_until: dt.datetime,
    signed: dt.datetime | None = None,
) -> bytes:
    """Return the CBOR-encoded IssuerSigned structure for a single mdoc.

    `device_key` is the holder's COSE_Key (decoded map), bound into the MSO so that the
    wallet can later produce DeviceAuth in proximity (ISO 18013-5) and remote (OpenID4VP)
    presentations.
    """
    signed = signed or dt.datetime.now(dt.timezone.utc)
    ns_items: dict[str, list[cbor2.CBORTag]] = {}
    value_digests: dict[str, dict[int, bytes]] = {}
    for ns, elements in namespaces.items():
        # Randomised digest IDs so the ordering of elements is not leaked (18013-5 §9.1.2.5)
        ids = list(range(len(elements)))
        secrets.SystemRandom().shuffle(ids)
        items, digests = [], {}
        for digest_id, (name, value) in zip(ids, elements.items()):
            item_bytes = _encode_item(digest_id, name, value)
            tagged = cbor2.CBORTag(24, item_bytes)
            items.append(tagged)
            digests[digest_id] = hashlib.sha256(cbor2.dumps(tagged)).digest()
        ns_items[ns] = items
        value_digests[ns] = digests

    mso = {
        "version": "1.0",
        "digestAlgorithm": "SHA-256",
        "valueDigests": value_digests,
        "deviceKeyInfo": {"deviceKey": device_key},
        "docType": doc_type,
        "validityInfo": {
            "signed": tdate(signed),
            "validFrom": tdate(valid_from),
            "validUntil": tdate(valid_until),
        },
    }
    payload = cbor2.dumps(cbor2.CBORTag(24, cbor2.dumps(mso)))
    ds_der = ds_cert.public_bytes(serialization.Encoding.DER)
    issuer_auth = cose.sign1(ds_key, payload, {cose.HDR_X5CHAIN: ds_der})
    return cbor2.dumps({"nameSpaces": ns_items, "issuerAuth": issuer_auth})
