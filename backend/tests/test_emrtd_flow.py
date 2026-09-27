"""End-to-end: virtual passport -> challenge -> AA/CA -> PA -> PID mdoc -> mdoc verification."""
import base64
import datetime as dt

import cbor2
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from app import main
from app.mdoc.cose import ec_to_cose_key
from app.mdoc.verifier import verify_issuer_signed
from tests import emrtd_factory as f


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def device_key() -> tuple[ec.EllipticCurvePrivateKey, str]:
    k = ec.generate_private_key(ec.SECP256R1())
    return k, b64(cbor2.dumps(ec_to_cose_key(k.public_key())))


def run_flow(client, passport: f.VirtualPassport, *, tamper_aa=False, skip_ca=False, cipher=("3DES", 16),
             extra: dict | None = None):
    ch = client.post("/api/v1/emrtd/challenge",
                     json={"dg14": b64(passport.dgs[14]) if 14 in passport.dgs else None}).json()
    challenge = bytes.fromhex(ch["aa_challenge"])
    aa_sig = passport.internal_authenticate(challenge) if passport.aa_key else None
    if tamper_aa and aa_sig:
        aa_sig = passport.internal_authenticate(b"\x00" * 8)
    ca_resp = None
    if ch["chip_authentication"] and not skip_ca:
        ca_resp = passport.chip_authenticate_and_transmit(ch["chip_authentication"], *cipher)
    _, dk = device_key()
    body = {
        "session_id": ch["session_id"],
        "sod": b64(passport.sod),
        "data_groups": {str(k): b64(v) for k, v in passport.dgs.items()},
        "active_auth_signature": b64(aa_sig) if aa_sig else None,
        "chip_auth_response": b64(ca_resp) if ca_resp else None,
        "access_control": "PACE",
        "device_key": dk,
        **(extra or {}),
    }
    return client.post("/api/v1/emrtd/issue", json=body).json()


def statuses(res):
    return {s["name"]: s["status"] for s in res["report"]}


def test_full_flow_rsa_aa_and_ca(client):
    p = f.make_passport(aa="rsa", ca=True)
    res = run_flow(client, p)
    assert res["decision"] == "accepted", res["reasons"]
    st = statuses(res)
    assert st["passive_authentication"] in ("pass", "warn")  # warn: no CSCA configured
    assert st["active_authentication"] == "pass"
    assert st["chip_authentication"] == "pass"
    cred = res["credential"]
    issuer_signed = base64.urlsafe_b64decode(cred["issuer_signed"] + "==")
    out = verify_issuer_signed(issuer_signed, [main.state.pki.iaca_cert], "eu.europa.ec.eudi.pid.1")
    pid = out["claims"]["eu.europa.ec.eudi.pid.1"]
    assert pid["family_name"] == "ERIKSSON"
    assert pid["given_name"] == "ANNA MARIA"
    assert pid["birth_date"] == "1974-08-12"
    assert pid["age_over_18"] is True
    assert pid["portrait"][:3] == b"\xff\xd8\xff"
    assert pid["issuing_country"] == "EU"
    assert pid["place_of_birth"]["locality"] == "ZENITH"
    # the server's own verify endpoint agrees
    assert client.post("/api/v1/mdoc/verify", json={"issuer_signed": cred["issuer_signed"]}).json()["valid"]


def test_ec_aa_and_aes_ca(client):
    p = f.make_passport(aa="ec", ca=True, ca_cipher_suffix="2")
    res = run_flow(client, p, cipher=("AES", 16))
    assert res["decision"] == "accepted", res["reasons"]
    assert statuses(res)["chip_authentication"] == "pass"


def test_aes256_ca(client):
    p = f.make_passport(aa=None, ca=True, ca_cipher_suffix="4")
    res = run_flow(client, p, cipher=("AES", 32))
    assert res["decision"] == "accepted", res["reasons"]


def test_clone_without_ca_or_aa_is_rejected(client):
    """A clone copies all DGs + SOD (PA passes) but cannot answer AA/CA."""
    p = f.make_passport(aa="rsa", ca=True)
    res = run_flow(client, p, tamper_aa=True, skip_ca=True)
    assert res["decision"] == "rejected"
    assert "credential" not in res
    assert statuses(res)["active_authentication"] == "fail"


def test_modified_dg_fails_passive_auth(client):
    p = f.make_passport()
    p.dgs[1] = f.make_dg1(f.td3_mrz(surname="MALLORY"))
    res = run_flow(client, p)
    assert res["decision"] == "rejected"
    assert statuses(res)["passive_authentication"] == "fail"


def test_csca_chain(client, tmp_path):
    pki = f.make_pki(dsc_rsa=True)
    csca_dir = tmp_path / "csca"
    csca_dir.mkdir(exist_ok=True)
    (csca_dir / "csca.pem").write_bytes(pki.csca_cert.public_bytes(serialization.Encoding.PEM))
    from app.emrtd.csca_store import CscaStore

    main.state.csca = CscaStore.from_directory(csca_dir)
    main.state.settings.require_csca_trust = True
    res = run_flow(client, f.make_passport(pki=pki))
    assert res["decision"] == "accepted", res["reasons"]
    pa = next(s for s in res["report"] if s["name"] == "passive_authentication")
    assert pa["status"] == "pass"
    # a passport from an unknown CSCA is now refused
    res = run_flow(client, f.make_passport())
    assert res["decision"] == "rejected"


def test_expired_document_rejected(client):
    exp = (dt.date.today() - dt.timedelta(days=2)).strftime("%y%m%d")
    res = run_flow(client, f.make_passport(mrz=f.td3_mrz(exp=exp)))
    assert res["decision"] == "rejected"


def test_session_single_use(client):
    p = f.make_passport()
    ch = client.post("/api/v1/emrtd/challenge", json={}).json()
    _, dk = device_key()
    body = {"session_id": ch["session_id"], "sod": b64(p.sod), "data_groups": {"1": b64(p.dgs[1])},
            "device_key": dk}
    client.post("/api/v1/emrtd/issue", json=body)
    assert client.post("/api/v1/emrtd/issue", json=body).status_code == 404


def test_pki_endpoints(client):
    assert b"BEGIN CERTIFICATE" in client.get("/pki/iaca.pem").content
    assert client.get("/pki/crl.der").status_code == 200


def test_attestation_offer_carries_dtc(client):
    """After a chip issuance the app gets an offer for the attestations; the photo ID carries the
    eMRTD DTC namespace (EF.SOD + DG1 + DG2) so verifiers can re-run Passive Authentication."""
    from tests.test_oid4vci import proof, resolve, token

    p = f.make_passport(aa="rsa", ca=True)
    res = run_flow(client, p)
    offer = res["attestation_offer"]
    assert [c["id"] for c in offer["credentials"]] == ["org.iso.23220.photoID.1", "eu.europa.ec.av.1"]
    doc = resolve(client, offer["uri"])
    access = token(client, doc, None).json()["access_token"]
    nonce = client.post("/oid4vci/nonce").json()["c_nonce"]
    k = ec.generate_private_key(ec.SECP256R1())
    r = client.post("/oid4vci/credential", headers={"Authorization": f"Bearer {access}"},
                    json={"credential_configuration_id": "org.iso.23220.photoID.1", "proofs": {"jwt": [proof(k, nonce)]}})
    cred = r.json()["credentials"][0]["credential"]
    out = verify_issuer_signed(base64.urlsafe_b64decode(cred + "=" * (-len(cred) % 4)), [main.state.pki.iaca_cert])
    dtc = out["claims"]["org.iso.23220.dtc.1"]
    assert dtc["dtc_sod"] == p.sod and dtc["dtc_dg1"] == p.dgs[1] and dtc["dtc_dg2"] == p.dgs[2]


def test_selfie_checked_against_chip_portrait(client):
    from app import face

    if not face.engine("data/models").available():
        pytest.skip("face models not downloaded")
    selfie = open("tests/fixtures/face_public_domain.jpg", "rb").read()
    p = f.make_passport(aa="rsa", ca=True, face=selfie)
    # a selfie without the head-turn frames: face matches, but liveness is not proven
    res = run_flow(client, p, extra={"selfie": b64(selfie)})
    assert res["decision"] == "rejected"
    bio = next(s for s in res["report"] if s["name"] == "biometrics")
    assert {c["name"]: c["status"] for c in bio["checks"]}["face_match"] == "pass"
    assert "liveness check not completed" in res["reasons"]
    # a selfie of nobody -> rejected
    blank = f.face_jpeg()
    res = run_flow(client, p, extra={"selfie": b64(blank)})
    assert res["decision"] == "rejected" and "credential" not in res
