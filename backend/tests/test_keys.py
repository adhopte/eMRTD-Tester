"""Chips and CSCAs often encode EC keys with explicit domain parameters."""
from asn1crypto import keys
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app.emrtd.crypto_utils import _CURVE_PARAMS, load_spki

# brainpoolP256r1 base point / order
G = bytes.fromhex("04" "8BD2AEB9CB7E57CB2C4B482FFC81B7AFB9DE27E1E3BD23C23A4453BD9ACE3262"
                  "547EF835C3DAC4FD97F8461A14611DC9C27745132DED8E545C1D54C72F046997")
N = 0xA9FB57DBA1EEA9BC3E660A909D838D718C397AA3BF4A2FA6FFB3CC0C3A7D6F7B


def test_explicit_brainpool_params():
    k = ec.generate_private_key(ec.BrainpoolP256R1())
    point = k.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    p, a, b = _CURVE_PARAMS["brainpoolP256r1"]
    spki = keys.PublicKeyInfo({
        "algorithm": {"algorithm": "ec", "parameters": ("specified", {
            "version": "ecdpVer1",
            "field_id": {"field_type": "prime_field", "parameters": p},
            "curve": {"a": a.to_bytes(32, "big"), "b": b.to_bytes(32, "big")},
            "base": G,
            "order": N,
            "cofactor": 1,
        })},
        "public_key": point,
    })
    loaded = load_spki(spki.dump())
    assert loaded.kind == "EC"
    assert loaded.key.curve.name == "brainpoolP256r1"
    assert loaded.key.public_numbers() == k.public_key().public_numbers()
