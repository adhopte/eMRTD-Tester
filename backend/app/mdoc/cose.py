"""Minimal COSE_Sign1 (RFC 9052) and COSE_Key helpers for ISO/IEC 18013-5."""
from __future__ import annotations

import cbor2
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature

ALG_ES256, ALG_ES384, ALG_ES512 = -7, -35, -36
HDR_ALG, HDR_X5CHAIN = 1, 33

_ALG_HASH = {ALG_ES256: (hashes.SHA256, 32), ALG_ES384: (hashes.SHA384, 48), ALG_ES512: (hashes.SHA512, 66)}
_CURVE_ALG = {"secp256r1": ALG_ES256, "secp384r1": ALG_ES384, "secp521r1": ALG_ES512,
              "brainpoolP256r1": ALG_ES256, "brainpoolP384r1": ALG_ES384, "brainpoolP512r1": ALG_ES512}

# COSE_Key EC2 curve identifiers (RFC 9053 + IANA registry for brainpool)
_CRV = {1: ec.SECP256R1, 2: ec.SECP384R1, 3: ec.SECP521R1,
        256: ec.BrainpoolP256R1, 258: ec.BrainpoolP384R1, 259: ec.BrainpoolP512R1}
_CRV_ID = {"secp256r1": 1, "secp384r1": 2, "secp521r1": 3, "brainpoolP256r1": 256,
           "brainpoolP384r1": 258, "brainpoolP512r1": 259}


def sig_structure(protected: bytes, payload: bytes, external_aad: bytes = b"") -> bytes:
    return cbor2.dumps(["Signature1", protected, external_aad, payload])


def sign1(key: ec.EllipticCurvePrivateKey, payload: bytes, unprotected: dict | None = None) -> list:
    alg = _CURVE_ALG[key.curve.name]
    protected = cbor2.dumps({HDR_ALG: alg})
    hash_cls, _ = _ALG_HASH[alg]
    size = (key.curve.key_size + 7) // 8
    der = key.sign(sig_structure(protected, payload), ec.ECDSA(hash_cls()))
    r, s = decode_dss_signature(der)
    sig = r.to_bytes(size, "big") + s.to_bytes(size, "big")
    return [protected, unprotected or {}, payload, sig]


def verify1(pub: ec.EllipticCurvePublicKey, msg: list, external_aad: bytes = b"", detached_payload: bytes | None = None) -> None:
    protected, _unprot, payload, sig = msg
    alg = cbor2.loads(protected)[HDR_ALG]
    hash_cls, _ = _ALG_HASH[alg]
    size = (pub.curve.key_size + 7) // 8
    if len(sig) != 2 * size:
        raise ValueError("bad COSE signature length")
    der = encode_dss_signature(int.from_bytes(sig[:size], "big"), int.from_bytes(sig[size:], "big"))
    body = detached_payload if payload is None else payload
    pub.verify(der, sig_structure(protected, body, external_aad), ec.ECDSA(hash_cls()))


def ec_to_cose_key(pub: ec.EllipticCurvePublicKey) -> dict:
    n = pub.public_numbers()
    size = (pub.curve.key_size + 7) // 8
    return {1: 2, -1: _CRV_ID[pub.curve.name], -2: n.x.to_bytes(size, "big"), -3: n.y.to_bytes(size, "big")}


def cose_key_to_ec(key: dict) -> ec.EllipticCurvePublicKey:
    if key.get(1) != 2:
        raise ValueError("only EC2 COSE keys are supported as device keys")
    curve = _CRV[key[-1]]()
    x = int.from_bytes(key[-2], "big")
    y = key[-3]
    if isinstance(y, bool):
        raise ValueError("compressed device keys are not supported")
    return ec.EllipticCurvePublicNumbers(x, int.from_bytes(y, "big"), curve).public_key()
