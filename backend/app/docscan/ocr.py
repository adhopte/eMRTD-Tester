"""Optional server-side OCR with Tesseract (the app also sends on-device ML Kit OCR text)."""
from __future__ import annotations

import logging
import shutil

import cv2
import numpy as np

log = logging.getLogger(__name__)

MRZ_WHITELIST = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
# Bound each Tesseract call so a slow/low-CPU host never stalls a request indefinitely
OCR_TIMEOUT_S = 20


def available() -> bool:
    return shutil.which("tesseract") is not None


def _prep(img: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    if gray.shape[1] < 1400:
        s = 1400 / gray.shape[1]
        gray = cv2.resize(gray, None, fx=s, fy=s, interpolation=cv2.INTER_CUBIC)
    return cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]


def ocr_text(img: np.ndarray) -> str:
    if not available():
        return ""
    import pytesseract

    try:
        return pytesseract.image_to_string(_prep(img), config="--psm 3", timeout=OCR_TIMEOUT_S)
    except Exception as e:  # noqa: BLE001
        log.warning("tesseract failed: %s", e)
        return ""


def ocr_mrz(img: np.ndarray) -> str:
    """OCR the bottom third of the document where the MRZ lives."""
    if not available():
        return ""
    import pytesseract

    h = img.shape[0]
    region = img[int(h * 0.6):, :]
    try:
        return pytesseract.image_to_string(
            _prep(region), config=f"--psm 6 -c tessedit_char_whitelist={MRZ_WHITELIST}", timeout=OCR_TIMEOUT_S)
    except Exception as e:  # noqa: BLE001
        log.warning("tesseract MRZ OCR failed: %s", e)
        return ""
