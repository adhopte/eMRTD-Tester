"""Optional server-side OCR with Tesseract (the app also sends on-device ML Kit OCR text)."""
from __future__ import annotations

import logging
import shutil

import cv2
import numpy as np

log = logging.getLogger(__name__)

MRZ_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
# Bound each Tesseract call so a slow/low-CPU host never stalls a request indefinitely
OCR_TIMEOUT_S = 45


def available() -> bool:
    return shutil.which("tesseract") is not None


def _prep(img: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    if gray.shape[1] < 1400:
        s = 1400 / gray.shape[1]
        gray = cv2.resize(gray, None, fx=s, fy=s, interpolation=cv2.INTER_CUBIC)
    return cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]


def ocr_text(img: np.ndarray, max_side: int = 1300) -> str:
    """Full-text OCR of the document (visual zone) on a bounded-size image."""
    if not available():
        return ""
    import pytesseract

    h, w = img.shape[:2]
    scale = max_side / max(h, w)
    if scale < 1:
        img = cv2.resize(img, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
    try:
        gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
        return pytesseract.image_to_string(gray, config="--psm 3", timeout=OCR_TIMEOUT_S)
    except Exception as e:  # noqa: BLE001
        log.warning("tesseract failed: %s", e)
        return ""


def find_mrz_band(img: np.ndarray) -> np.ndarray | None:
    """Locate the MRZ: 2-3 long, dense lines of dark text in the lower part of the document.

    Blackhat morphology isolates dark text on a light background; a horizontal gradient and wide
    closing merge characters into line blobs; the widest wide-and-flat blob low on the page is
    the MRZ. Returns an upscaled crop, or None.
    """
    h, w = img.shape[:2]
    scale = 1000 / w
    small = cv2.resize(img, (1000, max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
    gray = cv2.GaussianBlur(cv2.cvtColor(small, cv2.COLOR_BGR2GRAY), (3, 3), 0)
    blackhat = cv2.morphologyEx(gray, cv2.MORPH_BLACKHAT, cv2.getStructuringElement(cv2.MORPH_RECT, (15, 6)))
    grad = np.absolute(cv2.Sobel(blackhat, cv2.CV_32F, 1, 0, ksize=-1))
    grad = (255 * (grad - grad.min()) / (grad.max() - grad.min() + 1e-6)).astype("uint8")
    grad = cv2.morphologyEx(grad, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (25, 5)))
    thresh = cv2.threshold(grad, 0, 255, cv2.THRESH_BINARY | cv2.THRESH_OTSU)[1]
    thresh = cv2.morphologyEx(thresh, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (31, 13)))
    thresh = cv2.erode(thresh, None, iterations=2)
    contours, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    lines = []
    for c in contours:
        x, y, cw, ch = cv2.boundingRect(c)
        if cw >= 0.3 * small.shape[1] and cw / max(ch, 1) >= 5 and y >= 0.3 * small.shape[0]:
            lines.append((x, y, cw, ch))
    if not lines:
        return None
    x, y, cw, ch = max(lines, key=lambda b: b[2] * b[3])
    # Grow the band with stacked MRZ lines of similar width (TD1 has 3, TD2/TD3 have 2)
    grown = True
    while grown:
        grown = False
        for bx, by, bw, bh in lines:
            overlap = min(x + cw, bx + bw) - max(x, bx)
            gap = max(by - (y + ch), y - (by + bh))
            inside = by >= y and by + bh <= y + ch
            if not inside and overlap >= 0.6 * min(cw, bw) and gap <= 1.5 * bh and (max(y + ch, by + bh) - min(y, by)) <= 6 * bh:
                x0_, y0_ = min(x, bx), min(y, by)
                cw, ch = max(x + cw, bx + bw) - x0_, max(y + ch, by + bh) - y0_
                x, y = x0_, y0_
                grown = True
    pad_x, pad_y = int(0.03 * cw), int(0.25 * ch)
    x0, y0 = max(0, x - pad_x), max(0, y - pad_y)
    x1, y1 = min(small.shape[1], x + cw + pad_x), min(small.shape[0], y + ch + pad_y)
    crop = img[int(y0 / scale):int(y1 / scale), int(x0 / scale):int(x1 / scale)]
    # OCR works best with character heights of ~30-40 px: aim for ~1600 px wide MRZ lines
    f = 1600 / max(1, crop.shape[1])
    return cv2.resize(crop, None, fx=f, fy=f, interpolation=cv2.INTER_CUBIC) if f > 1 else crop


def ocr_mrz(img: np.ndarray) -> str:
    """OCR the MRZ band (located by morphology), falling back to the bottom of the document."""
    if not available():
        return ""
    import pytesseract

    band = find_mrz_band(img)
    region = band if band is not None else img[int(img.shape[0] * 0.6):, :]
    try:
        return pytesseract.image_to_string(
            _prep(region), config=f"--psm 6 -c tessedit_char_whitelist={MRZ_WHITELIST}", timeout=OCR_TIMEOUT_S)
    except Exception as e:  # noqa: BLE001
        log.warning("tesseract MRZ OCR failed: %s", e)
        return ""
