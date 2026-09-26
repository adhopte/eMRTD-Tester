"""Render uploaded PDF scans to page images (for API clients that send PDFs instead of photos)."""
from __future__ import annotations

import io

MAX_SIDE = 3000
TARGET_DPI = 300


def is_pdf(data: bytes) -> bool:
    return data[:5] == b"%PDF-"


def render_pages(data: bytes, max_pages: int = 2) -> list[bytes]:
    """Render up to `max_pages` pages as JPEG (300 DPI, long side capped at 3000 px)."""
    import pypdfium2 as pdfium

    try:
        pdf = pdfium.PdfDocument(data)
    except pdfium.PdfiumError as e:
        msg = str(e).lower()
        if "password" in msg:
            raise ValueError("the PDF is password-protected") from e
        raise ValueError("not a readable PDF file") from e
    try:
        if len(pdf) == 0:
            raise ValueError("the PDF has no pages")
        out = []
        for i in range(min(len(pdf), max_pages)):
            page = pdf[i]
            w, h = page.get_size()  # points
            scale = TARGET_DPI / 72
            scale = min(scale, MAX_SIDE / (max(w, h) or 1))
            image = page.render(scale=scale, fill_color=(255, 255, 255, 255)).to_pil().convert("RGB")
            buf = io.BytesIO()
            image.save(buf, format="JPEG", quality=92)
            out.append(buf.getvalue())
            page.close()
        return out
    finally:
        pdf.close()
