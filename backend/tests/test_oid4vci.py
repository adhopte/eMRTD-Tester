"""OpenID4VCI pre-authorized code flow, driven the way a wallet does it."""
import base64
import json
import time
from urllib.parse import parse_qs, unquote, urlparse

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

from app import main
from app.mdoc.verifier import verify_issuer_signed

ISSUER = "http://testserver"
SUBJECT = {"family_name": "Mustermann", "given_name": "Erika", "birth_date": "1964-08-12", "sex": "F",
           "nationality": "DEU", "document_number": "C01X00T47", "document_expiry": "2031-10-31"}


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def proof(key: ec.EllipticCurvePrivateKey, nonce: str, aud: str = ISSUER, typ: str = "openid4vci-proof+jwt",
          iat: float | None = None) -> str:
    nums = key.public_key().public_numbers()
    jwk = {"kty": "EC", "crv": "P-256", "x": b64u(nums.x.to_bytes(32, "big")), "y": b64u(nums.y.to_bytes(32, "big"))}
    header = b64u(json.dumps({"typ": typ, "alg": "ES256", "jwk": jwk}).encode())
    payload = b64u(json.dumps({"aud": aud, "iat": int(iat or time.time()), "nonce": nonce}).encode())
    r, s = decode_dss_signature(key.sign(f"{header}.{payload}".encode(), ec.ECDSA(hashes.SHA256())))
    return f"{header}.{payload}.{b64u(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


@pytest.fixture
def offer(client):
    portrait = open("tests/fixtures/face_public_domain.jpg", "rb").read()
    r = client.post("/api/v1/oid4vci/offers", json={"subject": {**SUBJECT, "portrait": base64.b64encode(portrait).decode()}})
    assert r.status_code == 200, r.text
    return r.json()


def resolve(client, offer_uri: str) -> dict:
    assert offer_uri.startswith("openid-credential-offer://")
    ref = unquote(parse_qs(urlparse(offer_uri).query)["credential_offer_uri"][0])
    assert ref.startswith(ISSUER)
    return client.get(ref.removeprefix(ISSUER)).json()


def token(client, offer_doc: dict, tx_code: str | None):
    grant = offer_doc["grants"]["urn:ietf:params:oauth:grant-type:pre-authorized_code"]
    form = {"grant_type": "urn:ietf:params:oauth:grant-type:pre-authorized_code",
            "pre-authorized_code": grant["pre-authorized_code"]}
    if tx_code is not None:
        form["tx_code"] = tx_code
    return client.post("/oid4vci/token", data=form)


def test_metadata(client):
    md = client.get("/.well-known/openid-credential-issuer").json()
    assert md["credential_issuer"] == ISSUER
    pid = md["credential_configurations_supported"]["eu.europa.ec.eudi.pid_mso_mdoc"]
    assert pid["format"] == "mso_mdoc" and pid["doctype"] == "eu.europa.ec.eudi.pid.1"
    assert pid["credential_signing_alg_values_supported"] == [-7]
    assert {"org.iso.23220.photoID.1", "eu.europa.ec.av.1"} <= set(md["credential_configurations_supported"])
    asm = client.get("/.well-known/oauth-authorization-server").json()
    assert asm["token_endpoint"] == f"{ISSUER}/oid4vci/token"


def test_full_flow_all_types(client, offer):
    doc = resolve(client, offer["credential_offer_uri"])
    assert doc["credential_issuer"] == ISSUER
    assert doc["grants"]["urn:ietf:params:oauth:grant-type:pre-authorized_code"]["tx_code"]["length"] == 6

    assert token(client, doc, "000000" if offer["tx_code"] != "000000" else "111111").status_code == 400
    tr = token(client, doc, offer["tx_code"])
    assert tr.status_code == 200, tr.text
    access = tr.json()["access_token"]
    assert token(client, doc, offer["tx_code"]).status_code == 400  # single use

    trusted = [main.state.pki.iaca_cert]
    for cid, doctype in [("eu.europa.ec.eudi.pid_mso_mdoc", "eu.europa.ec.eudi.pid.1"),
                         ("org.iso.23220.photoID.1", "org.iso.23220.photoID.1"),
                         ("eu.europa.ec.av.1", "eu.europa.ec.av.1")]:
        nonce = client.post("/oid4vci/nonce").json()["c_nonce"]
        keys = [ec.generate_private_key(ec.SECP256R1()) for _ in range(2)]
        r = client.post("/oid4vci/credential", headers={"Authorization": f"Bearer {access}"},
                        json={"credential_configuration_id": cid, "proofs": {"jwt": [proof(k, nonce) for k in keys]}})
        assert r.status_code == 200, r.text
        creds = r.json()["credentials"]
        assert len(creds) == 2
        for k, c in zip(keys, creds):
            raw = base64.urlsafe_b64decode(c["credential"] + "=" * (-len(c["credential"]) % 4))
            out = verify_issuer_signed(raw, trusted, doctype)
            nums = k.public_key().public_numbers()
            assert out["device_key"][-2] == nums.x.to_bytes(32, "big")
            assert out["device_key"][-3] == nums.y.to_bytes(32, "big")
            if cid == "eu.europa.ec.eudi.pid_mso_mdoc":
                assert out["claims"]["eu.europa.ec.eudi.pid.1"]["family_name"] == "MUSTERMANN"
                assert out["claims"]["eu.europa.ec.eudi.pid.1"]["portrait"][:2] == b"\xff\xd8"
                ev = out["claims"]["org.emrtd-tester.evidence.1"]
                assert ev["evidence_type"] == "manual_entry_unverified"
            elif cid == "org.iso.23220.photoID.1":
                assert out["claims"]["org.iso.23220.1"]["portrait"][:2] == b"\xff\xd8"
                assert out["claims"]["org.iso.23220.photoID.1"]["travel_document_number"] == "C01X00T47"
            else:
                av = out["claims"]["eu.europa.ec.av.1"]
                assert av["age_over_18"] is True and "family_name" not in av
        # the same configuration cannot be issued twice on one offer
        again = client.post("/oid4vci/credential", headers={"Authorization": f"Bearer {access}"},
                            json={"credential_configuration_id": cid, "proofs": {"jwt": [proof(keys[0], nonce)]}})
        assert again.status_code == 400
    assert client.get(f"/oid4vci/offers/{offer['offer_id']}/status").json()["status"] == "issued"


def test_proof_checks(client, offer):
    doc = resolve(client, offer["credential_offer_uri"])
    access = token(client, doc, offer["tx_code"]).json()["access_token"]
    nonce = client.post("/oid4vci/nonce").json()["c_nonce"]
    k = ec.generate_private_key(ec.SECP256R1())
    auth = {"Authorization": f"Bearer {access}"}
    cid = "eu.europa.ec.av.1"
    cases = [
        (proof(k, "not-a-nonce"), "invalid_nonce"),
        (proof(k, nonce, aud="https://evil.example"), "invalid_proof"),
        (proof(k, nonce, typ="JWT"), "invalid_proof"),
        (proof(k, nonce, iat=time.time() - 3600), "invalid_proof"),
        (proof(k, nonce)[:-6] + "AAAAAA", "invalid_proof"),
    ]
    for jwt, err in cases:
        r = client.post("/oid4vci/credential", headers=auth,
                        json={"credential_configuration_id": cid, "proofs": {"jwt": [jwt]}})
        assert r.status_code == 400 and r.json()["error"] == err, (err, r.text)
    r = client.post("/oid4vci/credential", headers={"Authorization": "Bearer nope"},
                    json={"credential_configuration_id": cid, "proofs": {"jwt": [proof(k, nonce)]}})
    assert r.status_code == 401
    r = client.post("/oid4vci/credential", headers=auth,
                    json={"credential_configuration_id": "unknown", "proofs": {"jwt": [proof(k, nonce)]}})
    assert r.status_code == 400


def test_tx_code_lockout(client, offer):
    doc = resolve(client, offer["credential_offer_uri"])
    wrong = "123456" if offer["tx_code"] != "123456" else "654321"
    for _ in range(5):
        assert token(client, doc, wrong).status_code == 400
    assert token(client, doc, offer["tx_code"]).status_code == 400
    assert client.get(f"/oid4vci/offers/{offer['offer_id']}/status").json()["status"] == "blocked"


def test_offer_without_tx_code(client):
    r = client.post("/api/v1/oid4vci/offers",
                    json={"subject": SUBJECT, "tx_code": False, "credentials": ["eu.europa.ec.av.1"]}).json()
    assert r["tx_code"] is None
    doc = resolve(client, r["credential_offer_uri"])
    assert "tx_code" not in doc["grants"]["urn:ietf:params:oauth:grant-type:pre-authorized_code"]
    assert token(client, doc, None).status_code == 200


def test_portal_pages(client):
    assert "Create offer QR code" in client.get("/issuer").text
    form = {k: v for k, v in SUBJECT.items()}
    form.update({"mode": "manual", "credentials": ["eu.europa.ec.av.1", "org.iso.23220.photoID.1"], "tx_code": "1"})
    r = client.post("/issuer/offer", data=form)
    assert r.status_code == 200, r.text
    assert "<svg" in r.text and "openid-credential-offer://" in r.text


def jwk_of(key: ec.EllipticCurvePrivateKey) -> dict:
    n = key.public_key().public_numbers()
    return {"kty": "EC", "crv": "P-256", "x": b64u(n.x.to_bytes(32, "big")), "y": b64u(n.y.to_bytes(32, "big"))}


def attested_proof(signing_key, kid: int, attestation: str, nonce: str) -> str:
    header = b64u(json.dumps({"typ": "openid4vci-proof+jwt", "alg": "ES256", "kid": str(kid),
                              "key_attestation": attestation}).encode())
    payload = b64u(json.dumps({"aud": ISSUER, "iat": int(time.time()), "nonce": nonce}).encode())
    r, s = decode_dss_signature(signing_key.sign(f"{header}.{payload}".encode(), ec.ECDSA(hashes.SHA256())))
    return f"{header}.{payload}.{b64u(r.to_bytes(32, 'big') + s.to_bytes(32, 'big'))}"


def test_key_attestation_proofs(client, offer):
    """EUDI wallet-core style: the proof carries a Wallet Provider key attestation (WUA) and every
    attested key receives a credential."""
    doc = resolve(client, offer["credential_offer_uri"])
    access = token(client, doc, offer["tx_code"]).json()["access_token"]
    nonce = client.post("/oid4vci/nonce").json()["c_nonce"]
    keys = [ec.generate_private_key(ec.SECP256R1()) for _ in range(2)]
    att = client.post("/wallet-provider/key-attestation",
                      json={"keys": [jwk_of(k) for k in keys], "nonce": nonce}).json()["key_attestation"]
    r = client.post("/oid4vci/credential", headers={"Authorization": f"Bearer {access}"},
                    json={"credential_configuration_id": "eu.europa.ec.eudi.pid_mso_mdoc",
                          "proofs": {"jwt": [attested_proof(keys[1], 1, att, nonce)]}})
    assert r.status_code == 200, r.text
    creds = r.json()["credentials"]
    assert len(creds) == 2
    for k, c in zip(keys, creds):
        raw = base64.urlsafe_b64decode(c["credential"] + "=" * (-len(c["credential"]) % 4))
        out = verify_issuer_signed(raw, [main.state.pki.iaca_cert], "eu.europa.ec.eudi.pid.1")
        assert out["device_key"][-2] == k.public_key().public_numbers().x.to_bytes(32, "big")

    # a proof signed by a key that is not the referenced attested key fails
    other = ec.generate_private_key(ec.SECP256R1())
    r = client.post("/oid4vci/credential", headers={"Authorization": f"Bearer {access}"},
                    json={"credential_configuration_id": "eu.europa.ec.av.1",
                          "proofs": {"jwt": [attested_proof(other, 0, att, nonce)]}})
    assert r.status_code == 400 and r.json()["error"] == "invalid_proof"
    # an attestation from anyone but the trusted Wallet Provider fails
    from app.wallet_provider import WalletProvider, load_or_create
    import tempfile
    rogue: WalletProvider = load_or_create(tempfile.mkdtemp())
    fake = rogue.key_attestation([jwk_of(keys[0])], nonce)
    r = client.post("/oid4vci/credential", headers={"Authorization": f"Bearer {access}"},
                    json={"credential_configuration_id": "eu.europa.ec.av.1",
                          "proofs": {"jwt": [attested_proof(keys[0], 0, fake, nonce)]}})
    assert r.status_code == 400 and "key attestation rejected" in r.json()["error_description"]
