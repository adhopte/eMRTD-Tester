"""Crypto helpers for eMRTD keys.

eMRTD chips and CSCAs frequently encode EC keys with *explicit* domain parameters,
which `cryptography` refuses to load. We map explicit parameters back to a named
curve when possible, and fall back to raw modular arithmetic for DH keys.
"""
from __future__ import annotations

from dataclasses import dataclass

from asn1crypto import algos, keys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import dh, ec, rsa

_NAMED_CURVES: list[ec.EllipticCurve] = [
    ec.SECP192R1(), ec.SECP224R1(), ec.SECP256R1(), ec.SECP384R1(), ec.SECP521R1(),
    ec.BrainpoolP256R1(), ec.BrainpoolP384R1(), ec.BrainpoolP512R1(),
]

# (p, a, b) of the curves above; used to identify explicit parameter encodings
_CURVE_PARAMS: dict[str, tuple[int, int, int]] = {
    "secp192r1": (0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFF,
                  0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFC,
                  0x64210519E59C80E70FA7E9AB72243049FEB8DEECC146B9B1),
    "secp224r1": (0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF000000000000000000000001,
                  0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFE,
                  0xB4050A850C04B3ABF54132565044B0B7D7BFD8BA270B39432355FFB4),
    "secp256r1": (0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF,
                  0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC,
                  0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B),
    "secp384r1": (int("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF", 16),
                  int("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFC", 16),
                  int("B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE8141120314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF", 16)),
    "secp521r1": ((1 << 521) - 1, (1 << 521) - 4,
                  int("0051953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918EF109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46B503F00", 16)),
    "brainpoolP256r1": (0xA9FB57DBA1EEA9BC3E660A909D838D726E3BF623D52620282013481D1F6E5377,
                        0x7D5A0975FC2C3057EEF67530417AFFE7FB8055C126DC5C6CE94A4B44F330B5D9,
                        0x26DC5C6CE94A4B44F330B5D9BBD77CBF958416295CF7E1CE6BCCDC18FF8C07B6),
    "brainpoolP384r1": (int("8CB91E82A3386D280F5D6F7E50E641DF152F7109ED5456B412B1DA197FB71123ACD3A729901D1A71874700133107EC53", 16),
                        int("7BC382C63D8C150C3C72080ACE05AFA0C2BEA28E4FB22787139165EFBA91F90F8AA5814A503AD4EB04A8C7DD22CE2826", 16),
                        int("04A8C7DD22CE28268B39B55416F0447C2FB77DE107DCD2A62E880EA53EEB62D57CB4390295DBC9943AB78696FA504C11", 16)),
    "brainpoolP512r1": (int("AADD9DB8DBE9C48B3FD4E6AE33C9FC07CB308DB3B3C9D20ED6639CCA703308717D4D9B009BC66842AECDA12AE6A380E62881FF2F2D82C68528AA6056583A48F3", 16),
                        int("7830A3318B603B89E2327145AC234CC594CBDD8D3DF91610A83441CAEA9863BC2DED5D5AA8253AA10A2EF1C98B9AC8B57F1117A72BF2C7B9E7C1AC4D77FC94CA", 16),
                        int("3DF91610A83441CAEA9863BC2DED5D5AA8253AA10A2EF1C98B9AC8B57F1117A72BF2C7B9E7C1AC4D77FC94CADC083E67984050B75EBAE5DD2809BD638016F723", 16)),
}


def curve_by_name(name: str) -> ec.EllipticCurve:
    for c in _NAMED_CURVES:
        if c.name == name:
            return c
    raise ValueError(f"unsupported curve {name}")


@dataclass
class DhParams:
    p: int
    g: int
    q: int | None = None


@dataclass
class ChipPublicKey:
    """A public key as found in DG14/DG15/certificates, normalized for our use."""
    kind: str  # "EC", "RSA", "DH"
    key: object | None = None  # cryptography public key for EC/RSA
    dh_params: DhParams | None = None
    dh_y: int | None = None

    @property
    def curve(self) -> ec.EllipticCurve | None:
        return self.key.curve if isinstance(self.key, ec.EllipticCurvePublicKey) else None


def _explicit_to_named(params: keys.SpecifiedECDomain) -> ec.EllipticCurve:
    field_id = params["field_id"]
    p = field_id["parameters"].native
    a = int.from_bytes(params["curve"]["a"].native, "big")
    b = int.from_bytes(params["curve"]["b"].native, "big")
    for name, (pp, aa, bb) in _CURVE_PARAMS.items():
        if (pp, aa, bb) == (p, a, b):
            return curve_by_name(name)
    raise ValueError("explicit EC domain parameters do not match a supported named curve")


def load_spki(der: bytes) -> ChipPublicKey:
    """Load a SubjectPublicKeyInfo, tolerating explicit EC and DH (DHPublicNumber) parameters."""
    spki = keys.PublicKeyInfo.load(der)
    algo = spki["algorithm"]["algorithm"].native
    if algo == "rsa" or algo == "rsassa_pss":
        k = spki["public_key"].parsed
        return ChipPublicKey("RSA", rsa.RSAPublicNumbers(k["public_exponent"].native, k["modulus"].native).public_key())
    if algo == "ec":
        params = spki["algorithm"]["parameters"]
        point = _bitstring_bytes(spki)
        if params.name == "named":
            curve = curve_by_name(_asn1_curve_to_crypto(params.chosen.native))
        elif params.name == "specified":
            curve = _explicit_to_named(params.chosen)
        else:
            raise ValueError("implicitCA EC parameters are not supported")
        return ChipPublicKey("EC", ec.EllipticCurvePublicKey.from_encoded_point(curve, point))
    if algo in ("dh", "1.2.840.10046.2.1", "1.2.840.113549.1.3.1"):
        return _load_dh(spki)
    raise ValueError(f"unsupported public key algorithm {algo}")


def _bitstring_bytes(spki: keys.PublicKeyInfo) -> bytes:
    contents = spki["public_key"].contents
    return contents[1:]  # strip the BIT STRING unused-bits octet


def _load_dh(spki: keys.PublicKeyInfo) -> ChipPublicKey:
    from . import tlv

    # Parse manually to handle both PKCS#3 (p, g[, l]) and X9.42 (p, g, q, ...) layouts
    body = tlv.unwrap(spki["algorithm"]["parameters"].dump(), 0x30)
    ints = [int.from_bytes(t.value, "big") for t in tlv.iter_tlv(body) if t.tag == 0x02]
    y = int.from_bytes(tlv.unwrap(_bitstring_bytes(spki), 0x02), "big")
    q = ints[2] if algo_is_x942(spki) and len(ints) > 2 else None
    return ChipPublicKey("DH", None, DhParams(p=ints[0], g=ints[1], q=q), y)


def algo_is_x942(spki: keys.PublicKeyInfo) -> bool:
    return spki["algorithm"]["algorithm"].dotted == "1.2.840.10046.2.1"


def _asn1_curve_to_crypto(name: str) -> str:
    return {
        "secp192r1": "secp192r1", "secp224r1": "secp224r1", "secp256r1": "secp256r1",
        "secp384r1": "secp384r1", "secp521r1": "secp521r1",
        "brainpoolp256r1": "brainpoolP256r1", "brainpoolp384r1": "brainpoolP384r1",
        "brainpoolp512r1": "brainpoolP512r1",
        "1.3.36.3.3.2.8.1.1.7": "brainpoolP256r1", "1.3.36.3.3.2.8.1.1.11": "brainpoolP384r1",
        "1.3.36.3.3.2.8.1.1.13": "brainpoolP512r1",
    }.get(name, name)


HASHES = {
    "sha1": hashes.SHA1, "sha224": hashes.SHA224, "sha256": hashes.SHA256,
    "sha384": hashes.SHA384, "sha512": hashes.SHA512,
}


def hash_by_name(name: str) -> hashes.HashAlgorithm:
    return HASHES[name.lower().replace("-", "")]()


def digest(name: str, data: bytes) -> bytes:
    h = hashes.Hash(hash_by_name(name))
    h.update(data)
    return h.finalize()


def plain_to_der_ecdsa(sig: bytes) -> bytes:
    """Convert a TR-03111 plain (r||s) ECDSA signature to DER."""
    from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature

    half = len(sig) // 2
    return encode_dss_signature(int.from_bytes(sig[:half], "big"), int.from_bytes(sig[half:], "big"))


def ecdsa_to_der(signature: bytes, curve: ec.EllipticCurve) -> bytes:
    """Normalise an ECDSA signature to DER. eMRTD chips return TR-03111 plain r||s; CMS uses DER.

    Decide by structure, not by the first byte: a plain signature whose r starts with 0x30
    would otherwise be mistaken for DER (about 1 in 256 signatures).
    """
    size = curve_byte_len(curve)
    if len(signature) == 2 * size:
        return plain_to_der_ecdsa(signature)
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    decode_dss_signature(signature)  # raises ValueError if it is not valid DER either
    return signature


def ec_point_bytes(key: ec.EllipticCurvePublicKey) -> bytes:
    return key.public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)


def curve_byte_len(curve: ec.EllipticCurve) -> int:
    return (curve.key_size + 7) // 8


def load_certificate_public_key(cert_der: bytes) -> ChipPublicKey:
    from asn1crypto import x509

    c = x509.Certificate.load(cert_der)
    return load_spki(c["tbs_certificate"]["subject_public_key_info"].dump())


def verify_signature(pub: ChipPublicKey, signature: bytes, data: bytes, sig_algo: algos.SignedDigestAlgorithm,
                     fallback_hash: str | None = None) -> None:
    """Verify a CMS/X.509 signature; raises on failure."""
    from cryptography.hazmat.primitives.asymmetric import padding

    name = sig_algo.signature_algo
    if name == "rsassa_pss":
        hash_name = None
    else:
        try:
            hash_name = sig_algo.hash_algo
        except ValueError:
            if not fallback_hash:
                raise
            hash_name = fallback_hash
    if name == "rsassa_pkcs1v15":
        pub.key.verify(signature, data, padding.PKCS1v15(), hash_by_name(hash_name))
    elif name == "rsassa_pss":
        params = sig_algo["parameters"]
        h = hash_by_name(params["hash_algorithm"]["algorithm"].native)
        mgf_h = hash_by_name(params["mask_gen_algorithm"]["parameters"]["algorithm"].native)
        pub.key.verify(signature, data, padding.PSS(padding.MGF1(mgf_h), params["salt_length"].native), h)
    elif name == "ecdsa":
        pub.key.verify(ecdsa_to_der(signature, pub.key.curve), data, ec.ECDSA(hash_by_name(hash_name)))
    else:
        raise ValueError(f"unsupported signature algorithm {sig_algo['algorithm'].native}")


def dh_generate(params: DhParams) -> tuple[int, int]:
    """Generate an ephemeral DH key pair on chip parameters; returns (x, y)."""
    import secrets

    bits = (params.q.bit_length() if params.q else 256)
    x = secrets.randbits(bits) % ((params.q or params.p) - 2) + 1
    return x, pow(params.g, x, params.p)


__all__ = [
    "ChipPublicKey", "DhParams", "load_spki", "digest", "hash_by_name", "plain_to_der_ecdsa",
    "ec_point_bytes", "curve_byte_len", "verify_signature", "dh_generate", "load_certificate_public_key",
    "dh",
]
