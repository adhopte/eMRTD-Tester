"""ISO/IEC 9796-2 AA: chips may return s or n - s; both must verify for every challenge."""
import hashlib
import secrets

from cryptography.hazmat.primitives.asymmetric import rsa

from app.emrtd.active_auth import verify_rsa_9796_2


def _sign(key, challenge: bytes, flip: bool) -> bytes:
    n = key.public_key().public_numbers().n
    d = key.private_numbers().d
    k = (n.bit_length() + 7) // 8
    m1 = secrets.token_bytes(k - 20 - 2)
    f = b"\x6A" + m1 + hashlib.sha1(m1 + challenge).digest() + b"\xBC"
    s = pow(int.from_bytes(f, "big"), d, n)
    return (n - s if flip else s).to_bytes(k, "big")


def test_both_signature_representatives_verify():
    key = rsa.generate_private_key(65537, 1024)
    # ~1/128 of n - f values also end in 0xBC/0xCC; 400 rounds make hitting that case near-certain
    for i in range(400):
        challenge = secrets.token_bytes(8)
        assert verify_rsa_9796_2(key.public_key(), challenge, _sign(key, challenge, flip=bool(i % 2))) == "sha1"


def test_plain_ecdsa_signatures_starting_with_0x30_verify():
    """TR-03111 plain r||s signatures whose r begins with 0x30 must not be mistaken for DER."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

    from app.emrtd.active_auth import verify_ecdsa

    key = ec.generate_private_key(ec.SECP256R1())
    hits = 0
    while hits < 3:
        challenge = secrets.token_bytes(8)
        r, s = decode_dss_signature(key.sign(challenge, ec.ECDSA(hashes.SHA256())))
        plain = r.to_bytes(32, "big") + s.to_bytes(32, "big")
        if plain[0] != 0x30:
            continue
        hits += 1
        assert verify_ecdsa(key.public_key(), challenge, plain, "1.2.840.10045.4.3.2") == "sha256"
