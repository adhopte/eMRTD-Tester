"""ICAO 9303-11 §6.1 Active Authentication.

The server generates the 8-byte challenge, the phone relays it to the chip with
INTERNAL AUTHENTICATE, and the server verifies the chip's response against the
DG15 public key (which Passive Authentication has already bound to the SOD).
"""
from __future__ import annotations

import hmac

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric import ec, rsa

from . import crypto_utils as cu

# ISO/IEC 9796-2 hash identifiers used in the two-byte trailer (xx CC)
_TRAILER_HASHES = {0x33: "sha1", 0x34: "sha256", 0x35: "sha512", 0x36: "sha384", 0x38: "sha224"}

# ECDSA signature algorithm OIDs that DG14 ActiveAuthenticationInfo may reference
_ECDSA_OIDS = {
    "1.2.840.10045.4.1": "sha1", "1.2.840.10045.4.3.1": "sha224", "1.2.840.10045.4.3.2": "sha256",
    "1.2.840.10045.4.3.3": "sha384", "1.2.840.10045.4.3.4": "sha512",
    "0.4.0.127.0.7.1.1.4.1.1": "sha1", "0.4.0.127.0.7.1.1.4.1.2": "sha224",
    "0.4.0.127.0.7.1.1.4.1.3": "sha256", "0.4.0.127.0.7.1.1.4.1.4": "sha384",
    "0.4.0.127.0.7.1.1.4.1.5": "sha512",
}


class ActiveAuthError(ValueError):
    pass


def verify_rsa_9796_2(public_key: rsa.RSAPublicKey, challenge: bytes, signature: bytes) -> str:
    """Verify an ISO/IEC 9796-2 Digital Signature Scheme 1 signature. Returns the hash name used."""
    nums = public_key.public_numbers()
    n, e = nums.n, nums.e
    k = (n.bit_length() + 7) // 8
    s = int.from_bytes(signature, "big")
    if s >= n:
        raise ActiveAuthError("signature representative out of range")
    f = pow(s, e, n)
    # The signer may have returned min(s, n - s): recover the correct representative
    fb = f.to_bytes(k, "big")
    if fb[-1] not in (0xBC, 0xCC):
        f = n - f
        fb = f.to_bytes(k, "big")
    # Strip leading zero octets introduced by k*8 > |n|
    if fb[-1] == 0xBC:
        hash_name, t_len = "sha1", 1
    elif fb[-1] == 0xCC:
        hash_name = _TRAILER_HASHES.get(fb[-2])
        if hash_name is None:
            raise ActiveAuthError(f"unknown 9796-2 hash identifier 0x{fb[-2]:02x}")
        t_len = 2
    else:
        raise ActiveAuthError("invalid 9796-2 trailer")
    h_len = cu.hash_by_name(hash_name).digest_size
    # Header: first significant nibble must be 6 (partial recovery) or 4 (full recovery)
    stripped = fb.lstrip(b"\x00")
    header = stripped[0] >> 4
    if header not in (0x6, 0x4):
        raise ActiveAuthError(f"invalid 9796-2 header nibble {header:x}")
    body = stripped[:-t_len]
    digest = body[-h_len:]
    m1 = body[1:-h_len]  # skip the header octet (0x6A / 0x4B)
    if header == 0x4:
        # full recovery: padding of 0x0B...0xBA before the message; not used by eMRTDs with 8-byte challenges
        raise ActiveAuthError("full message recovery signatures are not expected for AA")
    if not hmac.compare_digest(cu.digest(hash_name, m1 + challenge), digest):
        raise ActiveAuthError("hash of recovered message and challenge does not match")
    return hash_name


def verify_ecdsa(public_key: ec.EllipticCurvePublicKey, challenge: bytes, signature: bytes,
                 signature_algorithm_oid: str | None) -> str:
    candidates = [_ECDSA_OIDS[signature_algorithm_oid]] if signature_algorithm_oid in _ECDSA_OIDS else \
        ["sha256", "sha1", "sha384", "sha512", "sha224"]
    der = signature if signature[:1] == b"\x30" else cu.plain_to_der_ecdsa(signature)
    for h in candidates:
        try:
            public_key.verify(der, challenge, ec.ECDSA(cu.hash_by_name(h)))
            return h
        except InvalidSignature:
            continue
    raise ActiveAuthError("ECDSA signature does not verify")


def verify(dg15_spki: bytes, challenge: bytes, signature: bytes, aa_sig_oid: str | None = None) -> dict:
    key = cu.load_spki(dg15_spki)
    if key.kind == "RSA":
        h = verify_rsa_9796_2(key.key, challenge, signature)
        return {"algorithm": "RSA ISO/IEC 9796-2 DS1", "hash": h, "key_bits": key.key.key_size}
    if key.kind == "EC":
        h = verify_ecdsa(key.key, challenge, signature, aa_sig_oid)
        return {"algorithm": "ECDSA", "hash": h, "curve": key.key.curve.name}
    raise ActiveAuthError(f"unsupported AA key type {key.kind}")
