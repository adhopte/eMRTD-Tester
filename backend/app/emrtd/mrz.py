"""ICAO 9303 MRZ parsing (TD1, TD2, TD3) with check-digit validation."""
from __future__ import annotations

import datetime as dt
import re
from dataclasses import dataclass, field

_WEIGHTS = (7, 3, 1)


def _char_value(c: str) -> int:
    if c.isdigit():
        return int(c)
    if "A" <= c <= "Z":
        return ord(c) - 55
    if c == "<":
        return 0
    raise ValueError(f"invalid MRZ character {c!r}")


def check_digit(data: str) -> str:
    return str(sum(_char_value(c) * _WEIGHTS[i % 3] for i, c in enumerate(data)) % 10)


def _cd_ok(data: str, cd: str) -> bool:
    # A '<' check digit is allowed for empty optional fields
    if cd == "<":
        return set(data) <= {"<"}
    return check_digit(data) == cd


def parse_yymmdd(s: str, *, future: bool) -> dt.date | None:
    """Resolve a 2-digit year. Birth dates are in the past; expiry dates usually in the future."""
    if not re.fullmatch(r"\d{6}", s):
        return None
    yy, mm, dd = int(s[:2]), int(s[2:4]), int(s[4:6])
    today = dt.date.today()
    century = 2000 if (yy + 2000) <= today.year + (20 if future else 0) else 1900
    try:
        return dt.date(century + yy, mm, dd)
    except ValueError:
        return None


def _name(field_: str) -> tuple[str, str]:
    parts = field_.split("<<", 1)
    primary = parts[0].replace("<", " ").strip()
    secondary = parts[1].replace("<", " ").strip() if len(parts) > 1 else ""
    secondary = re.sub(r"\s+", " ", secondary)
    return primary, secondary


@dataclass
class Mrz:
    format: str
    document_code: str
    issuing_state: str
    primary_identifier: str
    secondary_identifier: str
    document_number: str
    nationality: str
    birth_date_raw: str
    sex: str
    expiry_date_raw: str
    optional_data: str
    optional_data_2: str = ""
    checks: dict[str, bool] = field(default_factory=dict)
    raw: str = ""

    @property
    def valid(self) -> bool:
        return all(self.checks.values())

    @property
    def birth_date(self) -> dt.date | None:
        return parse_yymmdd(self.birth_date_raw, future=False)

    @property
    def expiry_date(self) -> dt.date | None:
        return parse_yymmdd(self.expiry_date_raw, future=True)

    def to_dict(self) -> dict:
        return {
            "format": self.format,
            "document_code": self.document_code,
            "issuing_state": self.issuing_state,
            "primary_identifier": self.primary_identifier,
            "secondary_identifier": self.secondary_identifier,
            "document_number": self.document_number,
            "nationality": self.nationality,
            "birth_date": self.birth_date.isoformat() if self.birth_date else None,
            "sex": self.sex,
            "expiry_date": self.expiry_date.isoformat() if self.expiry_date else None,
            "optional_data": self.optional_data,
            "check_digits": self.checks,
            "valid": self.valid,
        }


class MrzError(ValueError):
    pass


def normalize_lines(text: str) -> list[str]:
    lines = []
    for line in text.replace("\r", "\n").split("\n"):
        line = line.strip().upper().replace(" ", "").replace("«", "<")
        if len(line) >= 28 and re.fullmatch(r"[A-Z0-9<]+", line):
            lines.append(line)
    return lines


def parse(text: str) -> Mrz:
    """Parse an MRZ from raw text (DG1 content or OCR output)."""
    lines = normalize_lines(text)
    joined = "".join(lines)
    if len(lines) >= 3 and all(len(l) == 30 for l in lines[-3:]):
        return _parse_td1(lines[-3:])
    if len(lines) >= 2 and all(len(l) == 44 for l in lines[-2:]):
        return _parse_td3(lines[-2:])
    if len(lines) >= 2 and all(len(l) == 36 for l in lines[-2:]):
        return _parse_td2(lines[-2:])
    # DG1 stores the MRZ without line breaks
    if len(joined) == 90:
        return _parse_td1([joined[0:30], joined[30:60], joined[60:90]])
    if len(joined) == 88:
        return _parse_td3([joined[0:44], joined[44:88]])
    if len(joined) == 72:
        return _parse_td2([joined[0:36], joined[36:72]])
    raise MrzError("no TD1/TD2/TD3 MRZ found")


def _parse_td3(l: list[str]) -> Mrz:
    l1, l2 = l
    doc_no, doc_cd = l2[0:9], l2[9]
    dob, dob_cd = l2[13:19], l2[19]
    exp, exp_cd = l2[21:27], l2[27]
    opt, opt_cd = l2[28:42], l2[42]
    composite = l2[0:10] + l2[13:20] + l2[21:43]
    primary, secondary = _name(l1[5:44])
    return Mrz(
        format="TD3",
        document_code=l1[0:2].replace("<", ""),
        issuing_state=l1[2:5].replace("<", ""),
        primary_identifier=primary,
        secondary_identifier=secondary,
        document_number=doc_no.replace("<", ""),
        nationality=l2[10:13].replace("<", ""),
        birth_date_raw=dob,
        sex=l2[20],
        expiry_date_raw=exp,
        optional_data=opt.replace("<", ""),
        checks={
            "document_number": _cd_ok(doc_no, doc_cd),
            "birth_date": _cd_ok(dob, dob_cd),
            "expiry_date": _cd_ok(exp, exp_cd),
            "optional_data": _cd_ok(opt, opt_cd),
            "composite": _cd_ok(composite, l2[43]),
        },
        raw="\n".join(l),
    )


def _parse_td2(l: list[str]) -> Mrz:
    l1, l2 = l
    doc_no, doc_cd = l2[0:9], l2[9]
    dob, dob_cd = l2[13:19], l2[19]
    exp, exp_cd = l2[21:27], l2[27]
    composite = l2[0:10] + l2[13:20] + l2[21:35]
    primary, secondary = _name(l1[5:36])
    return Mrz(
        format="TD2",
        document_code=l1[0:2].replace("<", ""),
        issuing_state=l1[2:5].replace("<", ""),
        primary_identifier=primary,
        secondary_identifier=secondary,
        document_number=doc_no.replace("<", ""),
        nationality=l2[10:13].replace("<", ""),
        birth_date_raw=dob,
        sex=l2[20],
        expiry_date_raw=exp,
        optional_data=l2[28:35].replace("<", ""),
        checks={
            "document_number": _cd_ok(doc_no, doc_cd),
            "birth_date": _cd_ok(dob, dob_cd),
            "expiry_date": _cd_ok(exp, exp_cd),
            "composite": _cd_ok(composite, l2[35]),
        },
        raw="\n".join(l),
    )


def _parse_td1(l: list[str]) -> Mrz:
    l1, l2, l3 = l
    doc_no, doc_cd = l1[5:14], l1[14]
    opt1 = l1[15:30]
    # Long document numbers overflow into the optional data field (ICAO 9303-5 §4.2.4)
    if doc_cd == "<" and opt1.strip("<"):
        overflow = opt1.split("<", 1)[0]
        doc_no = doc_no + overflow[:-1]
        doc_cd = overflow[-1]
    dob, dob_cd = l2[0:6], l2[6]
    exp, exp_cd = l2[8:14], l2[14]
    composite = l1[5:30] + l2[0:7] + l2[8:15] + l2[18:29]
    primary, secondary = _name(l3)
    return Mrz(
        format="TD1",
        document_code=l1[0:2].replace("<", ""),
        issuing_state=l1[2:5].replace("<", ""),
        primary_identifier=primary,
        secondary_identifier=secondary,
        document_number=doc_no.replace("<", ""),
        nationality=l2[15:18].replace("<", ""),
        birth_date_raw=dob,
        sex=l2[7],
        expiry_date_raw=exp,
        optional_data=opt1.replace("<", ""),
        optional_data_2=l2[18:29].replace("<", ""),
        checks={
            "document_number": _cd_ok(doc_no, doc_cd),
            "birth_date": _cd_ok(dob, dob_cd),
            "expiry_date": _cd_ok(exp, exp_cd),
            "composite": _cd_ok(composite, l2[29]),
        },
        raw="\n".join(l),
    )


def find_in_text(text: str) -> Mrz | None:
    """Locate the best-scoring MRZ inside noisy OCR output."""
    lines = normalize_lines(_ocr_fix(text))
    candidates: list[Mrz] = []
    for size, width in ((3, 30), (2, 44), (2, 36)):
        for i in range(len(lines) - size + 1):
            window = lines[i:i + size]
            if all(len(x) == width for x in window):
                try:
                    candidates.append(parse("\n".join(window)))
                except (MrzError, ValueError):
                    pass
    if not candidates:
        return None
    return max(candidates, key=lambda m: sum(m.checks.values()))


def _ocr_fix(text: str) -> str:
    # OCR engines frequently render the OCR-B filler '<' as a guillemet
    return text.replace("«", "<").replace("‹", "<")
