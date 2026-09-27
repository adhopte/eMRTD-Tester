"""TEST Wallet Provider: key attestations (Wallet Unit Attestation, WUA) for wallet device keys.

In the EUDI architecture a Wallet Provider vouches for the keys held by a wallet unit. Issuers
accept an OpenID4VCI JWT proof only when its ``key_attestation`` header carries such an
attestation (OpenID4VCI 1.0, Appendix D), and the EUDI wallet-core library always sends one.

This provider signs ``key-attestation+jwt`` tokens listing the submitted public keys. It records,
but does not verify against Google's roots, the Android Keystore attestation chain the app sends,
so it asserts only a basic key-storage level. It exists so the whole flow can be exercised end to
end; it is not a certified Wallet Provider.
"""
from __future__ import annotations

import base64
import datetime as dt
import json
import pathlib
import time

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature, encode_dss_signature
from cryptography.x509.oid import NameOID

KEY_ATTESTATION_TYP = "key-attestation+jwt"
VALIDITY_S = 24 * 3600


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64u_dec(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def jwk_to_key(jwk: dict) -> ec.EllipticCurvePublicKey:
    if jwk.get("kty") != "EC" or jwk.get("crv") != "P-256" or "d" in jwk:
        raise ValueError("only public EC P-256 JWKs are supported")
    x, y = b64u_dec(jwk["x"]), b64u_dec(jwk["y"])
    return ec.EllipticCurvePublicNumbers(int.from_bytes(x, "big"), int.from_bytes(y, "big"), ec.SECP256R1()).public_key()


def es256_sign(key: ec.EllipticCurvePrivateKey, header: dict, payload: dict) -> str:
    h = b64u(json.dumps(header, separators=(",", ":")).encode())
    p = b64u(json.dumps(payload, separators=(",", ":")).encode())
    r, s = decode_dss_signature(key.sign(f"{h}.{p}".encode(), ec.ECDSA(hashes.SHA256())))
    return f"{h}.{p}.{b64u(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def es256_verify(pub: ec.EllipticCurvePublicKey, jwt: str) -> tuple[dict, dict]:
    h, p, s = jwt.split(".")
    sig = b64u_dec(s)
    if len(sig) != 64:
        raise ValueError("ES256 signature must be 64 bytes")
    der = encode_dss_signature(int.from_bytes(sig[:32], "big"), int.from_bytes(sig[32:], "big"))
    try:
        pub.verify(der, f"{h}.{p}".encode(), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as e:
        raise ValueError("signature does not verify") from e
    return json.loads(b64u_dec(h)), json.loads(b64u_dec(p))


class WalletProvider:
    def __init__(self, key: ec.EllipticCurvePrivateKey, cert: x509.Certificate) -> None:
        self.key, self.cert = key, cert

    @property
    def cert_der(self) -> bytes:
        return self.cert.public_bytes(serialization.Encoding.DER)

    def key_attestation(self, jwks: list[dict], nonce: str | None, key_storage: str = "iso_18045_basic") -> str:
        if not jwks or len(jwks) > 10:
            raise ValueError("between 1 and 10 keys can be attested at once")
        for j in jwks:
            jwk_to_key(j)  # validates
        now = int(time.time())
        payload = {
            "iss": "IN Groupe Wallet Provider (TEST)",
            "iat": now, "exp": now + VALIDITY_S,
            "attested_keys": [{k: j[k] for k in ("kty", "crv", "x", "y")} for j in jwks],
            "key_storage": [key_storage],
            "user_authentication": ["iso_18045_basic"],
        }
        if nonce:
            payload["nonce"] = nonce
        header = {"typ": KEY_ATTESTATION_TYP, "alg": "ES256", "x5c": [base64.b64encode(self.cert_der).decode()]}
        return es256_sign(self.key, header, payload)

    def verify_key_attestation(self, jwt: str) -> dict:
        """Check a key attestation signed by this provider; returns its claims."""
        header, claims = es256_verify(self.key.public_key(), jwt)
        if header.get("typ") != KEY_ATTESTATION_TYP:
            raise ValueError(f"key attestation typ must be {KEY_ATTESTATION_TYP}")
        if claims.get("exp", 0) < time.time():
            raise ValueError("key attestation expired")
        keys = claims.get("attested_keys")
        if not isinstance(keys, list) or not keys:
            raise ValueError("key attestation has no attested_keys")
        return claims


def load_or_create(pki_dir: str | pathlib.Path) -> WalletProvider:
    d = pathlib.Path(pki_dir)
    kp, cp = d / "wallet_provider.key", d / "wallet_provider.pem"
    if kp.exists() and cp.exists():
        key = serialization.load_pem_private_key(kp.read_bytes(), None)
        return WalletProvider(key, x509.load_pem_x509_certificate(cp.read_bytes()))
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COUNTRY_NAME, "FR"),
                      x509.NameAttribute(NameOID.ORGANIZATION_NAME, "IN Groupe"),
                      x509.NameAttribute(NameOID.COMMON_NAME, "IN Groupe Wallet Provider (TEST)")])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=5 * 365))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), critical=True)
            .sign(key, hashes.SHA256()))
    try:
        d.mkdir(parents=True, exist_ok=True)
        kp.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                         serialization.NoEncryption()))
        cp.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    except OSError:
        pass  # read-only deployment: keep the ephemeral key for this process
    return WalletProvider(key, cert)
