"""Document image verification + VIZ/MRZ extraction for documents without a (readable) chip."""
from __future__ import annotations

import datetime as dt
import difflib
import re
from dataclasses import dataclass, field

from ..emrtd import mrz as mrz_mod
from ..pid import IdentityEvidence
from ..report import Section, Status
from . import image_checks as ic
from . import ocr
from .providers import PROVIDERS

SPECIMEN_WORDS = ("SPECIMEN", "SPÉCIMEN", "MUSTER", "EXEMPLAR", "VOORBEELD", "MUESTRA", "CAMPIONE", "ESPÉCIME")


@dataclass
class ScanThresholds:
    min_short_side: int = 600
    min_sharpness: float = 60.0
    max_glare: float = 0.25        # fail only on extreme blow-out; white document areas are normal
    warn_glare: float = 0.04
    max_analysis_side: int = 1600  # analyse at this resolution to stay within small-instance memory
    min_colourfulness: float = 12.0
    max_moire: float = 60.0
    max_format_error: float = 0.08
    min_score: float = 0.75
    allow_specimen: bool = False


@dataclass
class ScanResult:
    sections: list[Section]
    score: float
    mrz: mrz_mod.Mrz | None
    evidence: IdentityEvidence | None
    decision: str
    reasons: list[str] = field(default_factory=list)


def _norm(s: str) -> str:
    return re.sub(r"[^A-Z0-9 ]", " ", s.upper())


def _fuzzy_contains(haystack: str, needle: str, threshold: float = 0.8) -> float:
    """Best similarity of `needle` against any same-length token window of `haystack`."""
    needle = _norm(needle).strip()
    if not needle:
        return 0.0
    tokens = _norm(haystack).split()
    n = max(1, len(needle.split()))
    best = 0.0
    for i in range(len(tokens)):
        window = " ".join(tokens[i:i + n])
        best = max(best, difflib.SequenceMatcher(None, window, needle).ratio())
        if best >= 0.999:
            break
    return best


def _date_variants(d: dt.date) -> list[str]:
    return [d.strftime(f) for f in ("%d.%m.%Y", "%d/%m/%Y", "%d-%m-%Y", "%d %m %Y", "%Y-%m-%d", "%d%m%Y",
                                    "%d %b %Y", "%d %B %Y", "%d.%m.%y")]


def analyze(front: bytes, back: bytes | None, device_ocr_text: str, document_kind: str | None,
            th: ScanThresholds, today: dt.date | None = None, image_source: str = "camera") -> ScanResult:
    today = today or dt.date.today()
    quality = Section("image_quality")
    authenticity = Section("document_authenticity_heuristics")
    content = Section("document_content")
    sections = [quality, authenticity, content]
    if image_source == "upload":
        authenticity.warn("capture_source", "image uploaded from the device, not captured live in the app "
                          "(it may have been edited; treat as lower assurance)")
    else:
        authenticity.passed("capture_source", "captured live with the app camera")
    weights: list[tuple[float, float]] = []  # (weight, score in [0,1])

    # Decode, remember the captured resolution, then analyse a bounded-size copy: full phone
    # photos in float would need several hundred MB and get the process OOM-killed on 512 MB hosts.
    original_sizes: dict[str, tuple[int, int]] = {}
    images: dict = {}
    for side, data in (("front", front), ("back", back)):
        if not data:
            continue
        full = ic.decode(data)
        original_sizes[side] = full.shape[1], full.shape[0]
        images[side] = ic.resize_max(full, th.max_analysis_side)
        del full

    geometry: dict[str, ic.DocumentGeometry] = {}
    for side, img in images.items():
        w, h = original_sizes[side]
        if min(h, w) >= th.min_short_side:
            quality.passed(f"{side}_resolution", f"{w}x{h}")
        else:
            quality.failed(f"{side}_resolution", f"{w}x{h} is below {th.min_short_side}px on the short side")
        sharp = ic.sharpness(img)
        (quality.passed if sharp >= th.min_sharpness else quality.failed)(
            f"{side}_sharpness", f"Laplacian variance {sharp:.0f} (min {th.min_sharpness:.0f})")
        weights.append((1.0, min(1.0, sharp / (th.min_sharpness * 2))))
        mean, glare, dark = ic.exposure(img)
        if glare > th.max_glare:
            quality.failed(f"{side}_glare", f"{glare:.1%} of pixels saturated")
        elif glare > th.warn_glare:
            quality.warn(f"{side}_glare", f"{glare:.1%} of pixels very bright (glare or white document areas)")
        elif mean < 40 or mean > 235:
            quality.warn(f"{side}_exposure", f"mean brightness {mean:.0f}")
        else:
            quality.passed(f"{side}_exposure", f"mean brightness {mean:.0f}, glare {glare:.1%}")
        geometry[side] = ic.find_document(img)

    front_doc = geometry["front"].warped
    g = geometry["front"]
    if g.format_error is not None and g.format_error <= th.max_format_error:
        authenticity.passed("document_format", f"{g.format_name}, aspect {g.aspect_ratio:.3f}"
                            + ("" if g.found else " (image assumed pre-cropped)"))
        weights.append((1.0, 1.0))
    else:
        authenticity.warn("document_format", f"aspect ratio {g.aspect_ratio:.3f} does not match an ICAO format"
                          + ("" if g.found else "; document edges not detected"))
        weights.append((1.0, 0.3))

    colour = ic.colourfulness(front_doc)
    if colour >= th.min_colourfulness:
        authenticity.passed("colour_print", f"colourfulness {colour:.1f}")
        weights.append((1.5, 1.0))
    else:
        authenticity.failed("colour_print", f"colourfulness {colour:.1f}: looks like a greyscale copy")
        weights.append((1.5, 0.0))

    moire = ic.moire_score(images["front"])
    if moire <= th.max_moire:
        authenticity.passed("screen_recapture", f"no moiré pattern (score {moire:.1f})")
        weights.append((1.5, 1.0))
    else:
        authenticity.failed("screen_recapture", f"periodic pattern suggests a photo of a screen (score {moire:.1f})")
        weights.append((1.5, 0.0))

    faces = ic.find_faces(front_doc)
    portrait = None
    if faces:
        portrait = ic.crop_portrait(front_doc, faces[0])
        authenticity.passed("portrait_present", f"{len(faces)} face(s) detected (incl. ghost image)" if len(faces) > 1
                            else "1 face detected")
        weights.append((1.0, 1.0))
    else:
        authenticity.failed("portrait_present", "no facial image found on the document")
        weights.append((1.0, 0.0))

    for provider in PROVIDERS:
        weights.append((3.0, provider.verify(front, back, authenticity)))

    # --- OCR / MRZ ---------------------------------------------------------
    # Server-side Tesseract is slow on small instances; only use it when the phone's OCR text
    # (ML Kit) does not already contain a check-digit-valid MRZ.
    server_text = ""
    server_mrz_text = ""
    device_mrz = mrz_mod.find_in_text(device_ocr_text or "")
    if device_mrz is None or not device_mrz.valid:
        for side in images:
            server_mrz_text += "\n" + ocr.ocr_mrz(geometry[side].warped)
        if not (device_ocr_text or "").strip():
            for side in images:
                server_text += "\n" + ocr.ocr_text(geometry[side].warped)
    all_text = "\n".join([device_ocr_text or "", server_mrz_text, server_text])
    mrz = mrz_mod.find_in_text(all_text)
    if mrz is None:
        content.failed("mrz_found", "no machine readable zone could be read")
        return _finish(sections, weights, None, None, th, image_source)
    content.passed("mrz_found", f"{mrz.format} MRZ read", mrz.to_dict())
    bad = [k for k, v in mrz.checks.items() if not v]
    if bad:
        content.failed("mrz_check_digits", "invalid check digit(s): " + ", ".join(bad))
        weights.append((3.0, 0.0))
    else:
        content.passed("mrz_check_digits", "all check digits valid")
        weights.append((3.0, 1.0))

    if document_kind == "passport" and mrz.format != "TD3":
        content.warn("document_kind", f"expected a passport (TD3) but read a {mrz.format} MRZ")
    elif document_kind == "id_card" and mrz.format == "TD3":
        content.warn("document_kind", "expected an ID card but read a passport MRZ")

    exp = mrz.expiry_date
    if exp is None:
        content.failed("document_expiry", "expiry date unreadable")
    elif exp < today:
        content.failed("document_expiry", f"document expired on {exp.isoformat()}")
    else:
        content.passed("document_expiry", f"valid until {exp.isoformat()}")
    if mrz.birth_date is None:
        content.failed("birth_date", "birth date unreadable or incomplete")

    words = set(re.findall(r"[^\W\d_]+", all_text.upper()))
    if words & set(SPECIMEN_WORDS):
        (content.warn if th.allow_specimen else content.failed)("specimen", "document is marked as a SPECIMEN")

    # VIZ vs MRZ consistency (text outside the MRZ lines)
    mrz_lines = set(mrz.raw.split("\n"))
    viz_text = "\n".join(l for l in all_text.split("\n") if l.strip().upper().replace(" ", "") not in mrz_lines)
    consistency = {
        "surname": _fuzzy_contains(viz_text, mrz.primary_identifier),
        "given_names": _fuzzy_contains(viz_text, mrz.secondary_identifier.split(" ")[0]) if mrz.secondary_identifier else 0.0,
        "document_number": _fuzzy_contains(viz_text, mrz.document_number),
    }
    if mrz.birth_date:
        consistency["birth_date"] = max(_fuzzy_contains(viz_text, v) for v in _date_variants(mrz.birth_date))
    matched = {k: v >= 0.8 for k, v in consistency.items()}
    ratio = sum(matched.values()) / len(matched)
    if ratio >= 0.75:
        content.passed("viz_mrz_consistency", f"{sum(matched.values())}/{len(matched)} VIZ fields match the MRZ",
                       {k: round(v, 2) for k, v in consistency.items()})
    elif ratio >= 0.5:
        content.warn("viz_mrz_consistency", f"only {sum(matched.values())}/{len(matched)} VIZ fields match the MRZ",
                     {k: round(v, 2) for k, v in consistency.items()})
    else:
        content.failed("viz_mrz_consistency", "visual inspection zone does not match the MRZ",
                       {k: round(v, 2) for k, v in consistency.items()})
    weights.append((2.0, ratio))

    evidence = None
    if mrz.birth_date:
        evidence = IdentityEvidence(
            family_name=mrz.primary_identifier,
            given_name=mrz.secondary_identifier,
            birth_date=mrz.birth_date,
            sex=mrz.sex,
            nationality=mrz.nationality,
            issuing_state=mrz.issuing_state,
            document_number=mrz.document_number,
            document_type=mrz.document_code,
            document_expiry=mrz.expiry_date,
            portrait_jpeg=portrait,
            evidence_type="document_image",
        )
    return _finish(sections, weights, mrz, evidence, th, image_source)


def _finish(sections, weights, mrz, evidence, th: ScanThresholds, image_source: str = "camera") -> ScanResult:
    total = sum(w for w, _ in weights) or 1.0
    score = sum(w * s for w, s in weights) / total
    reasons = [f"{s.name}.{c.name}: {c.detail}" for s in sections for c in s.checks if c.status == Status.FAIL]
    if reasons or evidence is None:
        decision = "rejected"
    elif score < th.min_score:
        decision = "rejected"
        reasons.append(f"overall score {score:.2f} below threshold {th.min_score:.2f}")
    else:
        decision = "accepted"
    if evidence is not None:
        evidence.evidence_checks = {"score": round(score, 3), "decision": decision, "image_source": image_source}
    return ScanResult(sections, score, mrz, evidence, decision, reasons)
