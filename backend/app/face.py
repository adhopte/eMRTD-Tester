"""Selfie checks: face match against the document/chip portrait and a head-turn liveness check.

Face detection uses OpenCV YuNet and recognition uses SFace (both from the OpenCV model zoo,
Apache-2.0). The model files are fetched into ``data/models`` at image build time (see the
Dockerfile) and loaded lazily; when they are missing, biometric checks are reported as skipped.

Liveness here is an *active challenge* check: the app asks the user to turn their head both
ways (and blink), captures a frame per pose, and the server verifies that every frame shows
the same person and that the head pose really changed in opposite directions. That defeats a
printed photo or a static screen, but it is NOT a certified presentation attack detection
(ISO/IEC 30107-3) and a replayed video of the holder could pass it.
"""
from __future__ import annotations

import logging
import os
import threading
from dataclasses import dataclass

import cv2
import numpy as np

from .report import Section, Status

log = logging.getLogger(__name__)

YUNET_FILE = "face_detection_yunet_2023mar.onnx"
SFACE_FILE = "face_recognition_sface_2021dec.onnx"
# SFace cosine similarity threshold recommended by OpenCV for same identity (LFW-calibrated)
MATCH_THRESHOLD = 0.363
# Frames captured seconds apart in one session: pose changes lower the score a little
SESSION_THRESHOLD = 0.30
# Nose offset from the eye midpoint, in inter-ocular distances, for a head turn to count
MIN_TURN_YAW = 0.12
MAX_FRONTAL_YAW = 0.12


@dataclass
class Face:
    box: np.ndarray  # 15 floats as returned by YuNet (box, 5 landmarks, score)
    image: np.ndarray

    @property
    def score(self) -> float:
        return float(self.box[14])

    @property
    def width(self) -> float:
        return float(self.box[2])

    @property
    def yaw(self) -> float:
        """Signed horizontal head-turn estimate (0 = frontal)."""
        rex, lex, nx = self.box[4], self.box[6], self.box[8]
        eye_dist = abs(lex - rex) or 1.0
        return float((nx - (rex + lex) / 2) / eye_dist)


class FaceEngine:
    def __init__(self, model_dir: str) -> None:
        self._dir = model_dir
        self._lock = threading.Lock()
        self._det = None
        self._rec = None

    def available(self) -> bool:
        return all(os.path.isfile(os.path.join(self._dir, f)) for f in (YUNET_FILE, SFACE_FILE))

    def _load(self) -> None:
        if self._det is None:
            self._det = cv2.FaceDetectorYN.create(os.path.join(self._dir, YUNET_FILE), "", (320, 320), 0.7, 0.3, 50)
            self._rec = cv2.FaceRecognizerSF.create(os.path.join(self._dir, SFACE_FILE), "")

    @staticmethod
    def decode(data: bytes) -> np.ndarray:
        img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError("not a decodable image")
        h, w = img.shape[:2]
        # small document portraits are upscaled so YuNet (min ~10 px faces, tuned for ~320 px inputs) finds them
        scale = 640 / max(h, w) if max(h, w) > 640 else (320 / min(h, w) if min(h, w) < 320 else 1.0)
        if scale != 1.0:
            img = cv2.resize(img, (int(w * scale), int(h * scale)),
                             interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
        return img

    def faces(self, img: np.ndarray) -> list[Face]:
        with self._lock:
            self._load()
            self._det.setInputSize((img.shape[1], img.shape[0]))
            _, found = self._det.detect(img)
        if found is None:
            return []
        return sorted((Face(f, img) for f in found), key=lambda f: -f.width)

    def embedding(self, face: Face) -> np.ndarray:
        with self._lock:
            self._load()
            aligned = self._rec.alignCrop(face.image, face.box)
            return self._rec.feature(aligned).copy()

    def similarity(self, a: np.ndarray, b: np.ndarray) -> float:
        with self._lock:
            self._load()
            return float(self._rec.match(a, b, cv2.FaceRecognizerSF_FR_COSINE))


_engine: FaceEngine | None = None


def engine(model_dir: str) -> FaceEngine:
    global _engine
    if _engine is None or _engine._dir != model_dir:
        _engine = FaceEngine(model_dir)
    return _engine


def pose_changed(frontal: float, left: float, right: float) -> bool:
    """True when both turn frames are clearly turned, in opposite directions, and the selfie is
    closer to frontal than either turn. The sign convention (mirrored front camera or not) does
    not matter because only opposite signs are required."""
    return (abs(left) >= MIN_TURN_YAW and abs(right) >= MIN_TURN_YAW and left * right < 0
            and abs(frontal) <= max(MAX_FRONTAL_YAW, 0.6 * min(abs(left), abs(right))))


def _sharpness(face: Face) -> float:
    x, y, w, h = (int(v) for v in face.box[:4])
    crop = face.image[max(0, y):y + h, max(0, x):x + w]
    if crop.size == 0:
        return 0.0
    gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
    gray = cv2.resize(gray, (160, int(160 * gray.shape[0] / max(1, gray.shape[1]))))
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def _single_face(eng: FaceEngine, data: bytes, what: str, sec: Section) -> Face | None:
    try:
        img = eng.decode(data)
    except ValueError:
        sec.failed(f"{what}_image", f"{what} is not a valid image")
        return None
    faces = eng.faces(img)
    if not faces:
        sec.failed(f"{what}_face", f"no face found in the {what}")
        return None
    if len(faces) > 1 and faces[1].width > 0.5 * faces[0].width:
        sec.failed(f"{what}_face", f"{len(faces)} faces in the {what}; exactly one person must be visible")
        return None
    return faces[0]


def verify_selfie(model_dir: str, portrait: bytes | None, selfie: bytes,
                  liveness_frames: dict[str, bytes] | None = None,
                  device_report: dict | None = None) -> tuple[Section, dict, list[str]]:
    """Match the selfie with the document portrait and check the liveness frames.

    Returns the report section, a compact summary for the credential evidence, and the list of
    blocking failure reasons (empty when the selfie is accepted).
    """
    sec = Section("biometrics")
    reasons: list[str] = []
    summary: dict = {}
    eng = engine(model_dir)
    if not eng.available():
        sec.skipped("face_match", "face models not installed on this server")
        return sec, {"face_match": "not_performed"}, reasons

    s = _single_face(eng, selfie, "selfie", sec)
    if s is None:
        reasons.append("selfie: " + sec.checks[-1].detail)
        return sec, {"face_match": "failed"}, reasons
    rel = s.width / s.image.shape[1]
    sharp = _sharpness(s)
    if rel < 0.15:
        sec.warn("selfie_quality", f"face is small in the selfie ({rel:.0%} of the width)")
    elif sharp < 15:
        sec.warn("selfie_quality", f"selfie looks blurred (sharpness {sharp:.0f})")
    else:
        sec.passed("selfie_quality", f"face {rel:.0%} of the width, sharpness {sharp:.0f}")
    selfie_emb = eng.embedding(s)

    # --- 1:1 match against the portrait from the chip (DG2) or the document image ---
    if portrait is None:
        sec.failed("face_match", "no portrait available on the document to compare with")
        reasons.append("face match: document portrait unavailable")
        summary["face_match"] = "failed"
    else:
        p = _single_face(eng, portrait, "portrait", sec)
        if p is None:
            reasons.append("face match: " + sec.checks[-1].detail)
            summary["face_match"] = "failed"
        else:
            score = eng.similarity(selfie_emb, eng.embedding(p))
            data = {"cosine_similarity": round(score, 3), "threshold": MATCH_THRESHOLD, "algorithm": "OpenCV SFace"}
            if score >= MATCH_THRESHOLD:
                sec.passed("face_match", f"selfie matches the document portrait (similarity {score:.2f})", data)
                summary["face_match"] = "passed"
            else:
                sec.failed("face_match", f"selfie does not match the document portrait (similarity {score:.2f} "
                           f"< {MATCH_THRESHOLD})", data)
                reasons.append("the selfie does not match the portrait on the document")
                summary["face_match"] = "failed"
            summary["face_match_score"] = round(score, 3)

    # --- head-turn liveness (server-verified) ---
    frames = liveness_frames or {}
    turns = {k: v for k, v in frames.items() if k in ("turn_left", "turn_right")}
    if len(turns) < 2:
        sec.failed("liveness", "head-turn frames missing; liveness not proven")
        reasons.append("liveness check not completed")
        summary["liveness"] = "failed"
    else:
        yaws: dict[str, float] = {}
        ok = True
        for label, data in turns.items():
            f = _single_face(eng, data, label, sec)
            if f is None:
                ok = False
                continue
            sim = eng.similarity(selfie_emb, eng.embedding(f))
            if sim < SESSION_THRESHOLD:
                sec.failed(f"{label}_same_person", f"{label} frame shows a different face (similarity {sim:.2f})")
                ok = False
            yaws[label] = f.yaw
        frontal = s.yaw
        if ok and len(yaws) == 2:
            l, r = yaws["turn_left"], yaws["turn_right"]
            data = {"yaw_frontal": round(frontal, 2), "yaw_turn_left": round(l, 2), "yaw_turn_right": round(r, 2)}
            if pose_changed(frontal, l, r):
                sec.passed("liveness", "head turned both ways between frames of the same person "
                           "(active challenge; not a certified PAD)", data)
            else:
                sec.failed("liveness", "head pose did not change as requested between the frames", data)
                ok = False
        if device_report:
            sec.add("device_liveness_report", Status.PASS if ok else Status.WARN,
                    "challenges reported by the app", {k: v for k, v in device_report.items()
                                                      if isinstance(v, (str, int, float, bool, list))})
        summary["liveness"] = "passed" if ok else "failed"
        if not ok:
            reasons.append("liveness check failed")
    summary["liveness_method"] = "active head-turn challenge (not certified PAD)"
    return sec, summary, reasons


def portrait_jpeg(model_dir: str, data: bytes) -> bytes | None:
    """Head-and-shoulders JPEG crop of the single face in an uploaded photo (None if not exactly one).
    Without the face models the image is only re-encoded (max 640 px)."""
    eng = engine(model_dir)
    try:
        img = eng.decode(data)
    except ValueError:
        return None
    if eng.available():
        faces = eng.faces(img)
        if not faces or (len(faces) > 1 and faces[1].width > 0.5 * faces[0].width):
            return None
        x, y, w, h = (float(v) for v in faces[0].box[:4])
        cx, cy, ph = x + w / 2, y + h / 2, h * 2.0
        pw = ph * 35 / 45
        x0, y0 = int(max(0, cx - pw / 2)), int(max(0, cy - ph * 0.45))
        x1, y1 = int(min(img.shape[1], cx + pw / 2)), int(min(img.shape[0], cy + ph * 0.55))
        img = img[y0:y1, x0:x1]
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, 88])
    return buf.tobytes() if ok else None
