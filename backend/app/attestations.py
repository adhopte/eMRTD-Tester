"""Credential types this issuer can sign: the PID plus attestations derived from the same evidence.

Each type is issued as an ISO/IEC 18013-5 mdoc (mso_mdoc). The attestations reuse the verified
identity evidence of the PID:

* **Photo ID** (ISO/IEC TS 23220-4 `org.iso.23220.photoID.1`): name, birth date, portrait and
  document data; when the evidence comes from an eMRTD chip it also carries the ICAO Digital
  Travel Credential (DTC type 1) namespace with the chip's EF.SOD, DG1 and DG2, so a verifier can
  run Passive Authentication itself.
* **Age verification** (EU age verification blueprint `eu.europa.ec.av.1`): only age-over flags,
  no name and no portrait (data minimisation by design).
"""
from __future__ import annotations

import datetime as dt
from dataclasses import dataclass
from typing import Any, Callable

from .countries import to_alpha2
from .mdoc.issuer import full_date
from .pid import PID_DOCTYPE, PID_NAMESPACE, IdentityEvidence, _age, build_namespaces, pid_expiry

PHOTOID_DOCTYPE = "org.iso.23220.photoID.1"
ISO23220_NAMESPACE = "org.iso.23220.1"
PHOTOID_NAMESPACE = "org.iso.23220.photoID.1"
DTC_NAMESPACE = "org.iso.23220.dtc.1"
AV_DOCTYPE = "eu.europa.ec.av.1"
AV_NAMESPACE = "eu.europa.ec.av.1"


@dataclass(frozen=True)
class IssueContext:
    issuing_authority: str
    issuing_country: str
    issuance: dt.date
    max_validity_days: int


@dataclass(frozen=True)
class CredentialType:
    config_id: str
    doctype: str
    name: str
    description: str
    background_color: str
    text_color: str
    # (namespace, element, display name) advertised in the issuer metadata
    claims: tuple[tuple[str, str, str], ...]
    build: Callable[[IdentityEvidence, IssueContext], tuple[dict[str, dict[str, Any]], dt.date]]
    has_portrait: bool


def _pid(ev: IdentityEvidence, ctx: IssueContext):
    expiry = pid_expiry(ev, ctx.issuance, ctx.max_validity_days)
    return build_namespaces(ev, issuing_authority=ctx.issuing_authority, issuing_country=ctx.issuing_country,
                            issuance=ctx.issuance, expiry=expiry), expiry


def _photo_id(ev: IdentityEvidence, ctx: IssueContext):
    expiry = pid_expiry(ev, ctx.issuance, ctx.max_validity_days)
    age = _age(ev.birth_date, ctx.issuance)
    base: dict[str, Any] = {
        "family_name_unicode": ev.family_name,
        "given_name_unicode": ev.given_name,
        "birth_date": full_date(ev.birth_date),
        "issue_date": full_date(ctx.issuance),
        "expiry_date": full_date(expiry),
        "issuing_authority_unicode": ctx.issuing_authority,
        "issuing_country": ctx.issuing_country,
        "age_in_years": age,
        "age_over_18": age >= 18,
        "age_birth_year": ev.birth_date.year,
    }
    if ev.portrait_jpeg:
        base["portrait"] = ev.portrait_jpeg
    sex = {"M": 1, "F": 2}.get((ev.sex or "").upper()[:1])
    if sex:
        base["sex"] = sex
    nat = to_alpha2(ev.nationality)
    if nat:
        base["nationality"] = nat
    photo: dict[str, Any] = {"travel_document_number": ev.document_number}
    if ev.personal_number:
        photo["person_id"] = ev.personal_number
    if ev.place_of_birth:
        photo["birth_city"] = ev.place_of_birth[0]
    namespaces = {ISO23220_NAMESPACE: base, PHOTOID_NAMESPACE: photo}
    lds = ev.raw_lds or {}
    if ev.evidence_type == "emrtd_chip" and all(k in lds for k in ("sod", "dg1")):
        dtc: dict[str, Any] = {"dtc_version": "1.0", "dtc_sod": lds["sod"], "dtc_dg1": lds["dg1"]}
        if "dg2" in lds:
            dtc["dtc_dg2"] = lds["dg2"]
        namespaces[DTC_NAMESPACE] = dtc
    return namespaces, expiry


def _age_verification(ev: IdentityEvidence, ctx: IssueContext):
    age = _age(ev.birth_date, ctx.issuance)
    expiry = min(ctx.issuance + dt.timedelta(days=90), ev.document_expiry or dt.date.max)
    return {AV_NAMESPACE: {f"age_over_{n}": age >= n for n in (13, 15, 16, 18, 21, 23, 25, 65)}}, expiry


_PID_CLAIMS = (
    ("family_name", "Family name"), ("given_name", "Given name"), ("birth_date", "Date of birth"),
    ("portrait", "Portrait"), ("place_of_birth", "Place of birth"), ("nationality", "Nationality"),
    ("sex", "Sex"), ("resident_address", "Address"), ("personal_administrative_number", "Personal number"),
    ("age_over_18", "Age over 18"), ("age_over_21", "Age over 21"), ("age_in_years", "Age"),
    ("age_birth_year", "Year of birth"), ("issuing_authority", "Issuing authority"),
    ("issuing_country", "Issuing country"), ("document_number", "Document number"),
    ("date_of_issuance", "Issue date"), ("date_of_expiry", "Expiry date"),
)

CREDENTIAL_TYPES: dict[str, CredentialType] = {t.config_id: t for t in (
    CredentialType(
        "eu.europa.ec.eudi.pid_mso_mdoc", PID_DOCTYPE, "Person Identification Data (PID)",
        "EU Digital Identity PID derived from a verified identity document",
        "#002F87", "#FFFFFF", tuple((PID_NAMESPACE, n, d) for n, d in _PID_CLAIMS), _pid, True),
    CredentialType(
        "org.iso.23220.photoID.1", PHOTOID_DOCTYPE, "Photo ID",
        "ISO/IEC 23220 photo ID with portrait (and the eMRTD DTC data when read from a chip)",
        "#43B2ED", "#192C70",
        ((ISO23220_NAMESPACE, "family_name_unicode", "Family name"),
         (ISO23220_NAMESPACE, "given_name_unicode", "Given name"),
         (ISO23220_NAMESPACE, "birth_date", "Date of birth"), (ISO23220_NAMESPACE, "portrait", "Portrait"),
         (ISO23220_NAMESPACE, "sex", "Sex"), (ISO23220_NAMESPACE, "nationality", "Nationality"),
         (ISO23220_NAMESPACE, "age_over_18", "Age over 18"), (ISO23220_NAMESPACE, "issue_date", "Issue date"),
         (ISO23220_NAMESPACE, "expiry_date", "Expiry date"),
         (ISO23220_NAMESPACE, "issuing_authority_unicode", "Issuing authority"),
         (ISO23220_NAMESPACE, "issuing_country", "Issuing country"),
         (PHOTOID_NAMESPACE, "travel_document_number", "Travel document number"),
         (PHOTOID_NAMESPACE, "person_id", "Personal number"),
         (DTC_NAMESPACE, "dtc_sod", "eMRTD security object"), (DTC_NAMESPACE, "dtc_dg1", "eMRTD DG1 (MRZ)"),
         (DTC_NAMESPACE, "dtc_dg2", "eMRTD DG2 (face)")),
        _photo_id, True),
    CredentialType(
        "eu.europa.ec.av.1", AV_DOCTYPE, "Age verification",
        "Proof of age: age-over flags only, no name or portrait",
        "#EA0029", "#FFFFFF",
        tuple((AV_NAMESPACE, f"age_over_{n}", f"Age over {n}") for n in (13, 15, 16, 18, 21, 23, 25, 65)),
        _age_verification, False),
)}

ATTESTATION_CONFIG_IDS = ("org.iso.23220.photoID.1", "eu.europa.ec.av.1")
