"""Selfie face match / liveness. The fixture is a crop of NASA's public-domain astronaut portrait
(also shipped as scikit-image's `astronaut` sample)."""
import cv2
import numpy as np
import pytest

from app import face

MODELS = "data/models"
pytestmark = pytest.mark.skipif(not face.engine(MODELS).available(),
                                reason="face models not downloaded (see scripts/fetch_models.sh)")


def jpeg(img) -> bytes:
    return cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 80])[1].tobytes()


@pytest.fixture
def selfie() -> bytes:
    return open("tests/fixtures/face_public_domain.jpg", "rb").read()


def test_match_against_smaller_recompressed_portrait(selfie):
    img = cv2.imdecode(np.frombuffer(selfie, np.uint8), cv2.IMREAD_COLOR)
    portrait = jpeg(cv2.resize(img, (120, 165), interpolation=cv2.INTER_AREA))
    sec, summary, reasons = face.verify_selfie(MODELS, portrait, selfie)
    checks = {c.name: c for c in sec.checks}
    assert checks["face_match"].status.value == "pass"
    assert summary["face_match"] == "passed" and summary["face_match_score"] > 0.6
    # no head-turn frames -> liveness not proven
    assert summary["liveness"] == "failed" and "liveness check not completed" in reasons


def test_no_face_is_rejected(selfie):
    blank = jpeg(np.full((400, 300, 3), 200, np.uint8))
    _, summary, reasons = face.verify_selfie(MODELS, selfie, blank)
    assert summary["face_match"] == "failed" and reasons


def test_static_photo_fails_liveness(selfie):
    """Replaying the same still image for every challenge must not pass (no pose change)."""
    sec, summary, reasons = face.verify_selfie(MODELS, selfie, selfie, {"turn_left": selfie, "turn_right": selfie})
    assert summary["face_match"] == "passed"
    assert summary["liveness"] == "failed" and "liveness check failed" in reasons


def test_pose_rule():
    assert face.pose_changed(0.02, 0.3, -0.25)
    assert face.pose_changed(-0.05, -0.3, 0.2)
    assert not face.pose_changed(0.0, 0.3, 0.25)       # same direction twice
    assert not face.pose_changed(0.0, 0.05, -0.04)     # barely moved
    assert not face.pose_changed(0.35, 0.3, -0.3)      # "frontal" frame is itself turned


def test_portrait_crop(selfie):
    out = face.portrait_jpeg(MODELS, selfie)
    assert out is not None and out[:2] == b"\xff\xd8"
    assert face.portrait_jpeg(MODELS, jpeg(np.full((300, 300, 3), 255, np.uint8))) is None
