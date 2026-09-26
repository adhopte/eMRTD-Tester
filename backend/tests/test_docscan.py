import base64

import cbor2
import cv2
import numpy as np
import pytest
from cryptography.hazmat.primitives.asymmetric import ec

from app.docscan import image_checks
from app.mdoc.cose import ec_to_cose_key
from tests import emrtd_factory as f


def synthetic_card(mrz: str, viz: list[str], grey=False, blur=False) -> bytes:
    h, w = 1080, 1712  # ID-1 aspect on a larger canvas with margin
    canvas = np.full((h + 300, w + 300, 3), (40, 40, 40), np.uint8)
    card = np.zeros((h, w, 3), np.uint8)
    for y in range(h):  # guilloche-ish colourful background
        card[y, :] = (180 + 60 * np.sin(y / 40), 150 + 80 * np.cos(y / 55), 200)
    card[:, :, 1] = (card[:, :, 1].astype(int) + (np.arange(w) % 97)).clip(0, 255)
    cv2.rectangle(card, (60, 200), (460, 720), (120, 110, 100), -1)  # portrait area
    for i, line in enumerate(viz):
        cv2.putText(card, line, (520, 260 + i * 70), cv2.FONT_HERSHEY_SIMPLEX, 1.6, (20, 20, 20), 3)
    for i, line in enumerate(mrz.split("\n")):
        cv2.putText(card, line, (60, 820 + i * 80), cv2.FONT_HERSHEY_PLAIN, 3.6, (0, 0, 0), 4)
    canvas[150:150 + h, 150:150 + w] = card
    if grey:
        canvas = cv2.cvtColor(cv2.cvtColor(canvas, cv2.COLOR_BGR2GRAY), cv2.COLOR_GRAY2BGR)
    if blur:
        canvas = cv2.GaussianBlur(canvas, (41, 41), 0)
    return cv2.imencode(".jpg", canvas, [cv2.IMWRITE_JPEG_QUALITY, 92])[1].tobytes()


MRZ = f.td1_mrz()
VIZ = ["MUSTERMANN", "ERIKA", "T22000129", "12.08.1983", "DEUTSCH"]


@pytest.fixture
def fake_face(monkeypatch):
    monkeypatch.setattr(image_checks, "find_faces", lambda img: [(80, 250, 360, 420)])


def post(client, img, text, source="camera"):
    k = ec.generate_private_key(ec.SECP256R1())
    dk = base64.b64encode(cbor2.dumps(ec_to_cose_key(k.public_key()))).decode()
    return client.post("/api/v1/document/issue",
                       files={"front": ("front.jpg", img, "image/jpeg")},
                       data={"device_key": dk, "device_ocr_text": text, "document_kind": "id_card",
                             "image_source": source}).json()


def checks(res):
    return {f'{s["name"]}.{c["name"]}': c["status"] for s in res["report"] for c in s["checks"]}


def test_genuine_looking_card_is_accepted(client, fake_face):
    res = post(client, synthetic_card(MRZ, VIZ), "\n".join(VIZ) + "\n" + MRZ)
    assert res["decision"] == "accepted", (res["reasons"], checks(res))
    pid = res["credential"]["claims"]["eu.europa.ec.eudi.pid.1"]
    assert pid["family_name"] == "MUSTERMANN"
    assert pid["nationality"] == ["DE"]
    assert res["credential"]["claims"]["org.emrtd-tester.evidence.1"]["evidence_type"] == "document_image"


def test_photocopy_rejected(client, fake_face):
    res = post(client, synthetic_card(MRZ, VIZ, grey=True), "\n".join(VIZ) + "\n" + MRZ)
    assert res["decision"] == "rejected"
    assert checks(res)["document_authenticity_heuristics.colour_print"] == "fail"


def test_blurred_rejected(client, fake_face):
    res = post(client, synthetic_card(MRZ, VIZ, blur=True), "\n".join(VIZ) + "\n" + MRZ)
    assert res["decision"] == "rejected"
    assert checks(res)["image_quality.front_sharpness"] == "fail"


def test_viz_mismatch_rejected(client, fake_face):
    viz = ["SOMEONE", "ELSE", "X99999999", "01.01.1990"]
    res = post(client, synthetic_card(MRZ, viz), "\n".join(viz) + "\n" + MRZ)
    assert res["decision"] == "rejected"
    assert checks(res)["document_content.viz_mrz_consistency"] == "fail"


def test_no_face_rejected(client):
    res = post(client, synthetic_card(MRZ, VIZ), "\n".join(VIZ) + "\n" + MRZ)
    assert res["decision"] == "rejected"


def test_uploaded_image_is_flagged_but_accepted(client, fake_face):
    res = post(client, synthetic_card(MRZ, VIZ), "\n".join(VIZ) + "\n" + MRZ, source="upload")
    assert res["decision"] == "accepted", res["reasons"]
    assert checks(res)["document_authenticity_heuristics.capture_source"] == "warn"
    evidence = res["credential"]["claims"]["org.emrtd-tester.evidence.1"]
    assert evidence["verification_checks"]["image_source"] == "upload"
