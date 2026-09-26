"""Image-level checks for a photographed identity document.

These are *heuristics*, not a substitute for forensic document examination (UV/IR,
holograms, MLI/CLI, microprint). They reject low-quality captures, photocopies,
screen re-captures and obviously mis-shaped documents, and locate the portrait.
A commercial document-authenticity service can be plugged in via `providers.py`.
"""
from __future__ import annotations

from dataclasses import dataclass

import cv2
import numpy as np

# ICAO 9303-3 nominal formats (width / height)
FORMATS = {"ID-1 (TD1 card)": 85.6 / 53.98, "ID-2 (TD2)": 105.0 / 74.0, "ID-3 (passport page)": 125.0 / 88.0}


@dataclass
class DocumentGeometry:
    found: bool
    aspect_ratio: float | None
    format_name: str | None
    format_error: float | None
    coverage: float | None
    warped: np.ndarray


def decode(data: bytes) -> np.ndarray:
    arr = np.frombuffer(data, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("image could not be decoded")
    return img


def resize_max(img: np.ndarray, max_side: int = 1600) -> np.ndarray:
    h, w = img.shape[:2]
    s = max_side / max(h, w)
    return cv2.resize(img, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA) if s < 1 else img


def sharpness(img: np.ndarray) -> float:
    gray = cv2.cvtColor(resize_max(img, 1000), cv2.COLOR_BGR2GRAY)
    return float(cv2.Laplacian(gray, cv2.CV_64F).var())


def exposure(img: np.ndarray) -> tuple[float, float, float]:
    """Mean brightness, fraction of blown-out pixels (glare) and fraction of crushed blacks."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    return float(gray.mean()), float((gray >= 250).mean()), float((gray <= 5).mean())


def colourfulness(img: np.ndarray) -> float:
    """Hasler & Süsstrunk colourfulness metric; photocopies / greyscale prints score near 0."""
    b, g, r = cv2.split(img.astype("float"))
    rg = np.abs(r - g)
    yb = np.abs(0.5 * (r + g) - b)
    return float(np.sqrt(rg.std() ** 2 + yb.std() ** 2) + 0.3 * np.sqrt(rg.mean() ** 2 + yb.mean() ** 2))


def moire_score(img: np.ndarray) -> float:
    """Detect periodic high-frequency peaks typical of photographing a screen.

    Returns the ratio between the strongest isolated high-frequency peak and the median
    high-frequency magnitude. Natural document photos stay low (< ~30); screen captures
    with visible pixel grids produce sharp spectral spikes.
    """
    gray = cv2.cvtColor(resize_max(img, 1024), cv2.COLOR_BGR2GRAY).astype(np.float32)
    gray = gray * np.outer(np.hanning(gray.shape[0]), np.hanning(gray.shape[1]))
    mag = np.abs(np.fft.fftshift(np.fft.fft2(gray)))
    h, w = mag.shape
    yy, xx = np.ogrid[:h, :w]
    r = np.sqrt((yy - h / 2) ** 2 + (xx - w / 2) ** 2)
    band = mag[(r > min(h, w) * 0.25) & (r < min(h, w) * 0.48)]
    if band.size == 0:
        return 0.0
    return float(np.percentile(band, 99.99) / (np.median(band) + 1e-6))


def _order_corners(pts: np.ndarray) -> np.ndarray:
    s = pts.sum(axis=1)
    d = np.diff(pts, axis=1).ravel()
    return np.array([pts[np.argmin(s)], pts[np.argmin(d)], pts[np.argmax(s)], pts[np.argmax(d)]], dtype=np.float32)


def find_document(img: np.ndarray) -> DocumentGeometry:
    small = resize_max(img, 1200)
    scale = img.shape[1] / small.shape[1]
    gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), (5, 5), 0)
    edges = cv2.dilate(cv2.Canny(gray, 40, 120), np.ones((3, 3), np.uint8), iterations=2)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    area_img = small.shape[0] * small.shape[1]
    best = None
    for c in sorted(contours, key=cv2.contourArea, reverse=True)[:8]:
        area = cv2.contourArea(c)
        if area < 0.2 * area_img:
            break
        approx = cv2.approxPolyDP(c, 0.02 * cv2.arcLength(c, True), True)
        if len(approx) == 4 and cv2.isContourConvex(approx):
            best = (approx.reshape(4, 2).astype(np.float32) * scale, area / area_img)
            break
    if best is None:
        # Assume the capture is already cropped to the document
        h, w = img.shape[:2]
        ratio = max(w, h) / min(w, h)
        name, err = _closest_format(ratio)
        return DocumentGeometry(False, ratio, name, err, None, img)
    corners, coverage = best
    tl, tr, br, bl = _order_corners(corners)
    width = int(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl)))
    height = int(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr)))
    dst = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype=np.float32)
    warped = cv2.warpPerspective(img, cv2.getPerspectiveTransform(np.array([tl, tr, br, bl]), dst), (width, height))
    if height > width:
        warped = cv2.rotate(warped, cv2.ROTATE_90_CLOCKWISE)
    ratio = max(width, height) / max(1, min(width, height))
    name, err = _closest_format(ratio)
    return DocumentGeometry(True, ratio, name, err, coverage, warped)


def _closest_format(ratio: float) -> tuple[str, float]:
    name = min(FORMATS, key=lambda k: abs(FORMATS[k] - ratio))
    return name, abs(FORMATS[name] - ratio) / FORMATS[name]


_face_cascade = None


def find_faces(img: np.ndarray) -> list[tuple[int, int, int, int]]:
    global _face_cascade
    if _face_cascade is None:
        _face_cascade = cv2.CascadeClassifier(cv2.data.haarcascades + "haarcascade_frontalface_default.xml")
    gray = cv2.equalizeHist(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY))
    min_side = max(24, min(img.shape[:2]) // 12)
    faces = _face_cascade.detectMultiScale(gray, scaleFactor=1.08, minNeighbors=6, minSize=(min_side, min_side))
    return sorted((tuple(int(v) for v in f) for f in faces), key=lambda f: f[2] * f[3], reverse=True)


def crop_portrait(img: np.ndarray, face: tuple[int, int, int, int]) -> bytes:
    """Crop a passport-style head-and-shoulders portrait (roughly 35x45 aspect) as JPEG."""
    x, y, w, h = face
    cx, cy = x + w / 2, y + h / 2
    ph = h * 2.0
    pw = ph * 35 / 45
    x0, y0 = int(max(0, cx - pw / 2)), int(max(0, cy - ph * 0.45))
    x1, y1 = int(min(img.shape[1], cx + pw / 2)), int(min(img.shape[0], cy + ph * 0.55))
    crop = img[y0:y1, x0:x1]
    if max(crop.shape[:2]) > 640:
        crop = resize_max(crop, 640)
    ok, buf = cv2.imencode(".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, 90])
    if not ok:
        raise ValueError("could not encode portrait")
    return buf.tobytes()
