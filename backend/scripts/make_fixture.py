"""Issue a PID for a synthetic passport and write it as test fixtures for the Android unit tests.

    python scripts/make_fixture.py ../android/app/src/test/resources/fixtures
"""
import pathlib
import sys
import tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import base64  # noqa: E402

import cbor2  # noqa: E402
from cryptography.hazmat.primitives import serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ec  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from app import main  # noqa: E402
from app.config import Settings  # noqa: E402
from app.mdoc.cose import ec_to_cose_key  # noqa: E402
from tests import emrtd_factory as f  # noqa: E402


def b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def run(out_dir: pathlib.Path) -> None:
    tmp = tempfile.mkdtemp()
    main.init_state(Settings(pki_dir=f"{tmp}/pki", csca_dir=f"{tmp}/csca"))
    client = TestClient(main.app)
    p = f.make_passport()
    ch = client.post("/api/v1/emrtd/challenge", json={"dg14": b64(p.dgs[14])}).json()
    device_key = ec.generate_private_key(ec.SECP256R1())
    cose_key = cbor2.dumps(ec_to_cose_key(device_key.public_key()))
    res = client.post("/api/v1/emrtd/issue", json={
        "session_id": ch["session_id"],
        "sod": b64(p.sod),
        "data_groups": {str(k): b64(v) for k, v in p.dgs.items()},
        "active_auth_signature": b64(p.internal_authenticate(bytes.fromhex(ch["aa_challenge"]))),
        "chip_auth_response": b64(p.chip_authenticate_and_transmit(ch["chip_authentication"], "3DES", 16)),
        "access_control": "PACE",
        "device_key": b64(cose_key),
    }).json()
    assert res["decision"] == "accepted", res["reasons"]
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "issuer_signed.cbor").write_bytes(base64.urlsafe_b64decode(res["credential"]["issuer_signed"] + "=="))
    (out_dir / "device_key.cbor").write_bytes(cose_key)
    (out_dir / "iaca.der").write_bytes(main.state.pki.iaca_cert.public_bytes(serialization.Encoding.DER))
    print(f"fixtures written to {out_dir}")


if __name__ == "__main__":
    run(pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "fixtures"))
