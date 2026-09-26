"""Repair OCR errors in machine readable zones, using the ICAO check digits as the arbiter.

OCR engines misread OCR-B in predictable ways: 'Z'/'2'/'7', 'S'/'5'/'8', 'O'/'0'/'D', 'M' as
'N'/'H', and filler '<' as 'K', 'E', 'S', '5', sometimes adding or dropping a character.

Every MRZ position has a fixed character class and the numeric fields carry check digits, so a
repair is accepted only when:
  * each check-digit protected field has exactly ONE valid spelling at the minimum number of
    character corrections (ambiguity -> reject; we never guess), and
  * the whole MRZ, including the composite check digit, then validates.

The name line has no check digit: it is cleaned conservatively (filler noise removed) and, when
visual-zone text is available, name tokens are snapped to words printed on the document.
"""
from __future__ import annotations

import itertools
import re

from . import mrz as mrz_mod

MAX_FIELD_EDITS = 3

# Plausible OCR confusions, per target class
_DIGIT_ALTS = {"O": "0", "Q": "0", "D": "0", "U": "0", "C": "0", "I": "1", "L": "1", "T": "17", "J": "1",
               "Z": "27", "S": "58", "B": "8", "G": "6", "A": "4", "E": "8", "Y": "7", "?": "7",
               "0": "8", "1": "7", "3": "8", "5": "86", "6": "58", "7": "1", "8": "635", "9": "8"}
_ALPHA_ALTS = {"0": "OD", "1": "I", "2": "Z", "5": "S", "6": "G", "8": "B", "4": "A", "7": "T"}
_ALNUM_ALTS = {"0": "OD", "O": "0D", "D": "0O", "Q": "0O", "1": "IZ7", "I": "1L", "L": "1I", "Z": "27",
               "2": "Z7", "7": "Z1T", "T": "71", "5": "S8", "S": "58", "8": "B53", "B": "8", "6": "G5", "G": "6",
               "3": "8", "9": "8"}
_SEX = {"N": "M", "H": "M", "W": "M", "E": "F", "P": "F", "K": "<"}
_FILLER_LIKE = set("<KESC5Z")

# (start, end, check position, kind) of check-digit protected fields; kind: 'd' digits, 'x' alphanumeric
_TD3_FIELDS = [(0, 9, 9, "x"), (13, 19, 19, "d"), (21, 27, 27, "d"), (28, 42, 42, "x")]
_TD2_FIELDS = [(0, 9, 9, "x"), (13, 19, 19, "d"), (21, 27, 27, "d")]
_TD1_L1_FIELDS = [(5, 14, 14, "x")]
_TD1_L2_FIELDS = [(0, 6, 6, "d"), (8, 14, 14, "d")]


def _clean(line: str) -> str:
    line = line.upper().replace(" ", "").replace("«", "<").replace("‹", "<")
    return "".join(c if (c.isascii() and (c.isalnum() or c == "<")) else "<" for c in line)


def _normalize_filler(line: str) -> str:
    """In data lines, filler-like characters sandwiched between '<' are filler ('<<K<K<<' -> '<<<<<<<')."""
    prev = None
    while prev != line:
        prev = line
        line = re.sub(r"(?<=<)[KESC5Z]{1,3}(?=<)", lambda m: "<" * len(m.group(0)), line)
    return line


def _candidate_lines(text: str) -> list[str]:
    out = []
    for raw in text.replace("\r", "\n").split("\n"):
        line = _clean(raw)
        if len(line) >= 25 and ("<" in line or re.search(r"\d{6}", line)):
            out.append(line)
    return out


def _edits(line: str, diff: int) -> list[str]:
    """All variants with `diff` characters deleted (diff > 0) or '<' inserted (diff < 0)."""
    if diff > 0:
        return ["".join(c for i, c in enumerate(line) if i not in pos)
                for pos in itertools.combinations(range(len(line)), diff)]
    out = []
    for pos in itertools.combinations(range(len(line) + 1), -diff):
        s = line
        for p in sorted(pos, reverse=True):
            s = s[:p] + "<" + s[p:]
        out.append(s)
    return out


def _fit_length(line: str, length: int) -> list[tuple[str, int]]:
    """Length-corrected variants: absorb the difference in the longest filler run and/or with
    up to two single-character edits elsewhere (fewest edits first)."""
    diff = len(line) - length
    if diff == 0:
        return [(line, 0)]
    runs = sorted(((m.start(), m.end()) for m in re.finditer(r"<+", line)), key=lambda r: r[0] - r[1])
    out: list[str] = []
    for edits in range(0, 3):
        rest = diff - (edits if diff > 0 else -edits)
        if (diff > 0 and rest < 0) or (diff < 0 and rest > 0):
            break
        base = line
        if rest:
            if not runs:
                continue
            a, b = runs[0]
            new_len = (b - a) - rest
            if new_len < 1:
                continue
            base = line[:a] + "<" * new_len + line[b:]
        variants = [base] if edits == 0 else _edits(base, edits if diff > 0 else -edits)
        out.extend((v, edits) for v in variants if len(v) == length)  # filler resizing is free
    best: dict[str, int] = {}
    for v, c in out:
        best[v] = min(c, best.get(v, c))
    return sorted(best.items(), key=lambda vc: vc[1])


def _options(c: str, kind: str) -> list[tuple[str, int]]:
    """(character, cost) options for one position."""
    if kind == "d":
        base = [(c, 0)] if c.isdigit() else []
        return base + [(a, 1) for a in _DIGIT_ALTS.get(c, "")]
    if kind == "cd":  # check digit, or '<' for empty optional fields
        base = [(c, 0)] if (c.isdigit() or c == "<") else []
        return base + [(a, 1) for a in _DIGIT_ALTS.get(c, "")]
    # alphanumeric
    return [(c, 0)] + [(a, 1) for a in _ALNUM_ALTS.get(c, "")]


def _solve_field(field: str, cd: str, kind: str, limit: int = 8) -> list[tuple[str, int]]:
    """All spellings of field+check digit at the minimum number of corrections (may be several)."""
    positions = [_options(c, kind) for c in field] + [_options(cd, "cd")]
    if any(not p for p in positions):
        return []
    for budget in range(MAX_FIELD_EDITS + 1):
        found: list[str] = []
        for combo in _combos(positions, budget):
            value, check = combo[:-1], combo[-1]
            ok = set(value) <= {"<"} if check == "<" else mrz_mod.check_digit(value) == check
            if ok and combo not in found:
                found.append(combo)
                if len(found) > limit:
                    return []  # hopelessly ambiguous
        if found:
            return [(f, budget) for f in found]
    return []


def _combos(positions: list[list[tuple[str, int]]], budget: int):
    """Strings using exactly `budget` corrections (cost-1 substitutions)."""
    idx_with_alts = [i for i, opts in enumerate(positions) if any(cost == 1 for _, cost in opts)]
    for chosen in itertools.combinations(idx_with_alts, budget):
        chosen_set = set(chosen)
        per_pos = []
        for i, opts in enumerate(positions):
            if i in chosen_set:
                per_pos.append([ch for ch, cost in opts if cost == 1])
            else:
                zero = [ch for ch, cost in opts if cost == 0]
                if not zero:
                    break
                per_pos.append(zero)
        else:
            for p in itertools.product(*per_pos):
                yield "".join(p)


def _repair_line(line: str, fields: list[tuple[int, int, int, str]], alpha: list[tuple[int, int]],
                 sex_pos: int | None) -> list[tuple[str, int]]:
    """Candidate corrected lines with their substitution cost."""
    chars = list(line)
    cost = 0
    for a, b in alpha:
        for i in range(a, b):
            if chars[i] != "<" and not chars[i].isalpha():
                chars[i] = _ALPHA_ALTS.get(chars[i], chars[i])[0]
                cost += 1
    if sex_pos is not None and chars[sex_pos] in _SEX:
        chars[sex_pos] = _SEX[chars[sex_pos]]
        cost += 1
    results = [("".join(chars), cost)]
    for start, end, cdp, kind in fields:
        new = []
        for line, c in results:
            if kind == "x" and line[cdp] == "<" and not set(line[start:end]) <= {"<"}:
                new.append((line, c))  # TD1 long document number continues in the optional field
                continue
            for solved, fc in _solve_field(line[start:end], line[cdp], kind):
                new.append((line[:start] + solved[:-1] + line[end:cdp] + solved[-1] + line[cdp + 1:], c + fc))
        results = new[:64]
        if not results:
            return []
    return results


# ---------------------------------------------------------------------------
# Names (not check-digit protected)
# ---------------------------------------------------------------------------


def _is_garbage(token: str) -> bool:
    return bool(token) and (any(c.isdigit() for c in token)
                            or (set(token) <= _FILLER_LIKE and ("K" in token or len(token) <= 2)))


def _near(a: str, b: str) -> bool:
    """Edit distance <= 1 (substitution/insertion/deletion)."""
    if abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) <= 1
    if len(a) > len(b):
        a, b = b, a
    return any(a == b[:i] + b[i + 1:] for i in range(len(b)))


def _confirmed(token: str, words: set[str]) -> bool:
    """A name token is confirmed by the visual zone if a printed word equals it or is one letter off."""
    return token in words or any(len(w) >= 3 and _near(token, w) for w in words)


def _split_by_viz(token: str, words: set[str]) -> str:
    """ANNASMARIAS -> ANNA<MARIA when ANNA and MARIA (or near spellings) are printed on the document.

    The visual zone only confirms where names split; the MRZ's own letters are kept (the OCR-B
    MRZ is usually read more reliably than the printed fields)."""
    positions = [i for i, c in enumerate(token) if c in "KSECZ"]
    if len(positions) > 10:
        return token
    near_cost = 1.2  # a near (1-letter-off) match is weaker evidence than an exact printed word
    best, best_cost = token, (0.0 if token in words else near_cost) if _confirmed(token, words) else None
    for n in range(1, min(len(positions), 4) + 1):
        for combo in itertools.combinations(positions, n):
            parts = [p for p in "".join("<" if i in combo else c for i, c in enumerate(token)).split("<") if p]
            if not parts:
                continue
            # replacing a trailing filler-like letter (right before the '<<' filler) is cheap
            cost = sum(0.5 if i == len(token) - 1 else 1.0 for i in combo)
            if len(parts) > 1 and "".join(parts) in words:
                pass  # the printed text shows exactly these names run together (e.g. ANNAMARIA)
            elif all(_confirmed(p, words) for p in parts):
                cost += sum(0.0 if p in words else near_cost for p in parts)
            else:
                continue
            if best_cost is None or cost < best_cost:
                best, best_cost = "<".join(parts), cost
    return best


def _clean_names(field: str, words: set[str]) -> str:
    tokens = field.split("<")
    last = max((i for i, t in enumerate(tokens) if t and not _is_garbage(t)), default=-1)
    out = []
    for tok in tokens[:last + 1]:
        tok = "".join(_ALPHA_ALTS.get(c, c)[0] if c.isdigit() else c for c in tok)
        if tok and words:
            tok = _split_by_viz(tok, words)
        out.append(tok)
    return "<".join(out)


def _viz_words(viz_text: str) -> set[str]:
    return set(re.findall(r"[A-Z]{2,}", viz_text.upper()))


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def _valid(lines: list[str]) -> mrz_mod.Mrz | None:
    try:
        m = mrz_mod.parse("\n".join(lines))
    except (mrz_mod.MrzError, ValueError):
        return None
    return m if m.valid else None


def _shape_penalty(doc_number: str) -> int:
    """Document numbers usually put letters in a prefix (e.g. X1234567; L898902C3 is an exception)."""
    first_digit = next((i for i, c in enumerate(doc_number) if c.isdigit()), len(doc_number))
    return sum(c.isalpha() for c in doc_number[first_digit:])


def _with_clean_names(m: mrz_mod.Mrz, viz_text: str) -> mrz_mod.Mrz:
    """Names carry no check digit: strip OCR filler noise even when the data lines are valid."""
    raw_lines = viz_text.replace("\r", "\n").split("\n")
    words = _viz_words("\n".join(l for l in raw_lines if not ("<" in l or len(_clean(l)) >= 25)))
    lines = m.raw.split("\n")
    if m.format == "TD1":
        lines[2] = (_clean_names(lines[2], words) + "<" * 30)[:30]
    else:
        width = len(lines[0])
        lines[0] = (lines[0][:5] + _clean_names(lines[0][5:], words) + "<" * width)[:width]
    cleaned = _valid(lines)
    return cleaned or m


def _decide(valid: list[mrz_mod.Mrz], viz_text: str) -> mrz_mod.Mrz | None:
    """Accept only an unambiguous result. Ties (check digits cannot separate substitutions at
    positions with the same 7-3-1 weight) are broken by the printed document number, then by
    document-number shape; anything still tied is rejected."""
    unique = list({(m.document_number, m.birth_date_raw, m.expiry_date_raw, m.optional_data): m
                   for m in valid}.values())
    if len(unique) == 1:
        return unique[0]
    printed = re.sub(r"[^A-Z0-9]", "", viz_text.upper())
    in_viz = [m for m in unique if m.document_number and m.document_number in printed]
    if len(in_viz) == 1:
        return in_viz[0]
    pool = in_viz or unique
    best = min(_shape_penalty(m.document_number) for m in pool)
    shaped = [m for m in pool if _shape_penalty(m.document_number) == best]
    same_other_fields = len({(m.birth_date_raw, m.expiry_date_raw, m.optional_data) for m in shaped}) == 1
    return shaped[0] if len(shaped) == 1 and same_other_fields else None


def repair(text: str, viz_text: str = "") -> mrz_mod.Mrz | None:
    """Find and repair an MRZ in OCR text. Returns a check-digit-valid MRZ or None."""
    direct = mrz_mod.find_in_text(text)
    if direct is not None and direct.valid:
        return _with_clean_names(direct, viz_text or text)
    # Printed (visual zone) words come from non-MRZ lines only, never from the MRZ itself
    raw_lines = (viz_text or text).replace("\r", "\n").split("\n")
    viz_only = "\n".join(l for l in raw_lines if not ("<" in l or len(_clean(l)) >= 25))
    lines = _candidate_lines(text)
    words = _viz_words(viz_only)
    viz_text = viz_only
    for i in range(len(lines)):
        valid: list[tuple[mrz_mod.Mrz, int]] = []
        # TD3 (passports, 2x44) and TD2 (2x36): name line followed by the data line
        for width, fields in ((44, _TD3_FIELDS), (36, _TD2_FIELDS)):
            if i + 1 >= len(lines) or abs(len(lines[i + 1]) - width) > 4:
                continue
            head = lines[i][:5]
            if head[:1] not in ("P", "I", "A", "C", "V"):
                continue
            names = _clean_names(lines[i][5:], words)
            l1 = (head[:2] + "".join(_ALPHA_ALTS.get(c, c)[0] if c.isdigit() else c for c in head[2:5])
                  + names + "<" * width)[:width]
            for l2v, lc in _fit_length(_normalize_filler(lines[i + 1]), width):
                for l2, sc in _repair_line(l2v, fields, [(10, 13)], 20):
                    if m := _valid([l1, l2]):
                        valid.append((m, lc + sc))
            if valid:
                break
        # TD1 (ID cards, 3x30)
        if not valid and i + 1 < len(lines) and abs(len(lines[i]) - 30) <= 4 and abs(len(lines[i + 1]) - 30) <= 4:
            l3 = (_clean_names(lines[i + 2][:30], words) if i + 2 < len(lines) else "")
            l3 = (l3 + "<" * 30)[:30]
            line2_options = [(l2, lc + sc) for l2v, lc in _fit_length(_normalize_filler(lines[i + 1]), 30)
                             for l2, sc in _repair_line(l2v, _TD1_L2_FIELDS, [(15, 18)], 7)]
            for l1v, lc1 in _fit_length(_normalize_filler(lines[i]), 30):
                for l1, sc1 in _repair_line(l1v, _TD1_L1_FIELDS, [(2, 5)], None):
                    for l2, c2 in line2_options:
                        if m := _valid([l1, l2, l3]):
                            valid.append((m, lc1 + sc1 + c2))
        if valid:
            cheapest = min(c for _, c in valid)
            if cheapest > 4:
                return None  # too many corrections to trust
            decided = _decide([m for m, c in valid if c == cheapest], viz_text)
            return decided
    return None
