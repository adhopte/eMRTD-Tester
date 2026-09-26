"""Map identity evidence (eMRTD chip data or OCR'd document data) to an EUDI PID mdoc.

Attribute identifiers follow the PID Rulebook (ARF Annex 3.1) for the ISO/IEC 18013-5
encoding: doctype and namespace `eu.europa.ec.eudi.pid.1`. A handful of legacy element
names are emitted alongside the current ones so both older and newer verifiers find them.
"""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass, field
from typing import Any

from .countries import to_alpha2
from .mdoc.issuer import full_date

PID_DOCTYPE = "eu.europa.ec.eudi.pid.1"
PID_NAMESPACE = "eu.europa.ec.eudi.pid.1"
# Provenance of the evidence used to issue this PID (non-standard, informative)
EVIDENCE_NAMESPACE = "org.emrtd-tester.evidence.1"

# ISO/IEC 5218 codes used by the PID rulebook
_SEX = {"M": 1, "F": 2, "X": 0, "<": 0}


@dataclass
class IdentityEvidence:
    family_name: str
    given_name: str
    birth_date: dt.date
    sex: str
    nationality: str  # MRZ code, alpha-3 (or ICAO special code)
    issuing_state: str
    document_number: str
    document_type: str
    document_expiry: dt.date | None
    place_of_birth: list[str] = field(default_factory=list)
    address: list[str] = field(default_factory=list)
    personal_number: str | None = None
    portrait_jpeg: bytes | None = None
    evidence_type: str = "emrtd_chip"  # or "document_image"
    evidence_checks: dict[str, Any] = field(default_factory=dict)


def _age(birth: dt.date, today: dt.date) -> int:
    return today.year - birth.year - ((today.month, today.day) < (birth.month, birth.day))


def build_namespaces(ev: IdentityEvidence, *, issuing_authority: str, issuing_country: str,
                     issuance: dt.date, expiry: dt.date) -> dict[str, dict[str, Any]]:
    today = issuance
    age = _age(ev.birth_date, today)
    nat2 = to_alpha2(ev.nationality)
    pid: dict[str, Any] = {
        "family_name": ev.family_name,
        "given_name": ev.given_name,
        "birth_date": full_date(ev.birth_date),
    }
    if ev.place_of_birth:
        pob: dict[str, str] = {}
        last = to_alpha2(ev.place_of_birth[-1]) if len(ev.place_of_birth[-1]) in (1, 3) else None
        if last:
            pob["country"] = last
        pob["locality"] = ev.place_of_birth[0]
        pid["place_of_birth"] = pob
        pid["birth_place"] = ", ".join(ev.place_of_birth)  # legacy PID element
    if nat2:
        pid["nationality"] = [nat2]
    if ev.address:
        pid["resident_address"] = ", ".join(ev.address)
    if ev.personal_number:
        pid["personal_administrative_number"] = ev.personal_number
    if ev.portrait_jpeg:
        pid["portrait"] = ev.portrait_jpeg
    sex = _SEX.get(ev.sex.upper()[:1] if ev.sex else "<")
    if sex is not None:
        pid["sex"] = sex
    pid.update({
        "age_over_18": age >= 18,
        "age_over_21": age >= 21,
        "age_in_years": age,
        "age_birth_year": ev.birth_date.year,
        "issuing_authority": issuing_authority,
        "issuing_country": issuing_country,
        "document_number": ev.document_number,
        # current rulebook names and their legacy equivalents
        "date_of_issuance": full_date(issuance),
        "date_of_expiry": full_date(expiry),
        "issuance_date": full_date(issuance),
        "expiry_date": full_date(expiry),
    })
    evidence = {
        "evidence_type": ev.evidence_type,
        "source_document_type": ev.document_type,
        "source_document_issuing_state": ev.issuing_state,
        "source_document_expiry": full_date(ev.document_expiry) if ev.document_expiry else None,
        "verification_checks": {k: v for k, v in ev.evidence_checks.items()},
    }
    evidence = {k: v for k, v in evidence.items() if v is not None}
    return {PID_NAMESPACE: pid, EVIDENCE_NAMESPACE: evidence}


def pid_expiry(ev: IdentityEvidence, issuance: dt.date, max_validity_days: int) -> dt.date:
    """A PID never outlives the evidence document."""
    limit = issuance + dt.timedelta(days=max_validity_days)
    if ev.document_expiry and ev.document_expiry < limit:
        return ev.document_expiry
    return limit


def from_emrtd(mrz, dg11=None, portrait: bytes | None = None, checks: dict | None = None) -> IdentityEvidence:
    family, given = mrz.primary_identifier, mrz.secondary_identifier
    birth = mrz.birth_date
    if dg11 is not None:
        if dg11.full_name and "<<" in dg11.full_name:
            f, g = dg11.full_name.split("<<", 1)
            family, given = f.replace("<", " ").strip(), g.replace("<", " ").strip()
        if dg11.full_date_of_birth and len(dg11.full_date_of_birth) == 8 and dg11.full_date_of_birth.isdigit():
            try:
                birth = dt.date(int(dg11.full_date_of_birth[:4]), int(dg11.full_date_of_birth[4:6]),
                                int(dg11.full_date_of_birth[6:8]))
            except ValueError:
                pass
    if birth is None:
        raise ValueError("date of birth not available or incomplete in MRZ")
    return IdentityEvidence(
        family_name=family,
        given_name=given,
        birth_date=birth,
        sex=mrz.sex,
        nationality=mrz.nationality,
        issuing_state=mrz.issuing_state,
        document_number=mrz.document_number,
        document_type=mrz.document_code,
        document_expiry=mrz.expiry_date,
        place_of_birth=dg11.place_of_birth if dg11 else [],
        address=dg11.permanent_address if dg11 else [],
        personal_number=(dg11.personal_number if dg11 else None) or None,
        portrait_jpeg=portrait,
        evidence_type="emrtd_chip",
        evidence_checks=checks or {},
    )
