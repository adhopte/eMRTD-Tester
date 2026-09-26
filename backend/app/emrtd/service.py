"""Orchestrates eMRTD verification: parsing, PA, AA, CA and the issuance decision."""
from __future__ import annotations

import datetime as dt
import hashlib
from dataclasses import dataclass, field

from ..pid import IdentityEvidence, from_emrtd
from ..report import Section, Status
from ..sessions import IssuanceSession
from . import active_auth, chip_auth, lds
from .csca_store import CscaStore
from .passive_auth import passive_authentication


@dataclass
class EmrtdResult:
    sections: list[Section]
    mrz: dict | None
    evidence: IdentityEvidence | None
    decision: str
    reasons: list[str] = field(default_factory=list)
    facts: dict = field(default_factory=dict)


def verify_emrtd(
    session: IssuanceSession,
    sod_bytes: bytes,
    data_groups: dict[int, bytes],
    aa_signature: bytes | None,
    ca_response: bytes | None,
    access_control: str | None,
    csca_store: CscaStore,
    require_csca_trust: bool,
    require_chip_genuineness: bool,
    today: dt.date | None = None,
) -> EmrtdResult:
    today = today or dt.date.today()
    parsing = Section("document_data")
    sections = [parsing]
    reasons: list[str] = []
    facts: dict = {"access_control": access_control}

    if access_control:
        parsing.passed("access_control", f"chip accessed with {access_control}")

    # --- parse ---------------------------------------------------------------
    try:
        sod = lds.parse_sod(sod_bytes)
        parsing.passed("ef_sod", f"LDS security object, {sod.hash_algorithm}, DGs {sorted(sod.dg_hashes)}")
    except Exception as e:  # noqa: BLE001
        parsing.failed("ef_sod", f"cannot parse EF.SOD: {e}")
        return EmrtdResult(sections, None, None, "rejected", [f"EF.SOD invalid: {e}"])
    if 1 not in data_groups:
        parsing.failed("dg1", "DG1 (MRZ) missing")
        return EmrtdResult(sections, None, None, "rejected", ["DG1 missing"])
    try:
        mrz = lds.parse_dg1(data_groups[1])
        (parsing.passed if mrz.valid else parsing.warn)(
            "dg1", f"{mrz.format} MRZ" + ("" if mrz.valid else " with check digit errors"), mrz.to_dict())
    except Exception as e:  # noqa: BLE001
        parsing.failed("dg1", f"cannot parse DG1: {e}")
        return EmrtdResult(sections, None, None, "rejected", [f"DG1 invalid: {e}"])

    portrait = None
    if 2 in data_groups:
        try:
            faces = lds.parse_dg2(data_groups[2])
            if faces:
                portrait = lds.face_as_jpeg(faces[0])
                parsing.passed("dg2", f"facial image ({faces[0].mime_type}, {len(faces[0].data)} bytes)")
            else:
                parsing.warn("dg2", "no facial image found in DG2")
        except Exception as e:  # noqa: BLE001
            parsing.warn("dg2", f"cannot decode facial image: {e}")
    dg11 = None
    if 11 in data_groups:
        try:
            dg11 = lds.parse_dg11(data_groups[11])
            parsing.passed("dg11", "additional personal details parsed")
        except Exception as e:  # noqa: BLE001
            parsing.warn("dg11", f"cannot parse DG11: {e}")

    exp = mrz.expiry_date
    if exp is None or exp < today:
        parsing.failed("document_expiry", f"document expired ({mrz.expiry_date_raw})")
        reasons.append("document expired")
    else:
        parsing.passed("document_expiry", f"valid until {exp.isoformat()}")

    # --- passive authentication ---------------------------------------------
    pa_section, pa = passive_authentication(sod, data_groups, csca_store)
    sections.append(pa_section)
    facts["passive_auth"] = pa
    if not (pa["sod_signature"] and pa["dg_hashes"]):
        reasons.append("passive authentication failed")
    if require_csca_trust and not pa["csca_chain"]:
        reasons.append("document signer does not chain to a trusted CSCA")
    for num in (14, 15):
        if num in sod.dg_hashes and num not in data_groups:
            pa_section.warn(f"dg{num}_present", f"DG{num} listed in EF.SOD but not submitted")

    # --- active authentication ----------------------------------------------
    aa = Section("active_authentication")
    sections.append(aa)
    aa_ok = False
    sig_oid = None
    if 14 in data_groups:
        try:
            sig_oid = lds.parse_dg14(data_groups[14]).aa_signature_algorithm
        except Exception:  # noqa: BLE001
            pass
    if 15 not in data_groups:
        aa.skipped("aa", "chip does not support Active Authentication (no DG15)")
    elif aa_signature is None:
        aa.failed("aa", "chip supports AA but no response to the server challenge was provided")
    else:
        try:
            info = active_auth.verify(lds.parse_dg15(data_groups[15]), session.aa_challenge, aa_signature, sig_oid)
            aa.passed("aa", "chip signed the server challenge with the DG15 private key", info)
            aa_ok = True
        except Exception as e:  # noqa: BLE001
            aa.failed("aa", f"AA signature invalid: {e}")

    # --- chip authentication ------------------------------------------------
    ca = Section("chip_authentication")
    sections.append(ca)
    ca_ok = False
    ca_supported = False
    if 14 in data_groups:
        try:
            ca_supported = bool(lds.parse_dg14(data_groups[14]).ca_public_keys)
        except Exception as e:  # noqa: BLE001
            ca.warn("dg14", f"cannot parse DG14: {e}")
    if not ca_supported:
        ca.skipped("ca", "chip does not support Chip Authentication (no CA key in DG14)")
    elif session.ca is None:
        ca.failed("ca", f"no server CA challenge was prepared ({session.ca_error or 'DG14 not sent to /challenge'})")
    elif hashlib.sha256(data_groups[14]).digest() != session.dg14_hash:
        ca.failed("ca", "DG14 differs from the one used to prepare the CA challenge")
    elif ca_response is None:
        ca.failed("ca", "no chip response to the server-prepared CA command was provided")
    else:
        target = data_groups.get(session.ca.read_sfi)
        if target is None:
            ca.failed("ca", f"DG{session.ca.read_sfi} needed to check the CA response was not submitted")
        else:
            try:
                info = chip_auth.verify(session.ca, ca_response, target)
                ca.passed("ca", "chip derived the server's CA session keys (knows the DG14 private key)", info)
                ca_ok = True
            except Exception as e:  # noqa: BLE001
                ca.failed("ca", f"chip authentication failed: {e}")

    genuine = aa_ok or ca_ok
    facts.update({"active_auth": aa_ok, "chip_auth": ca_ok, "aa_supported": 15 in data_groups,
                  "ca_supported": ca_supported})
    if require_chip_genuineness and (15 in data_groups or ca_supported) and not genuine:
        reasons.append("chip genuineness (AA/CA) not proven — possible cloned chip")
    if aa.status == Status.FAIL and 15 in data_groups and aa_signature is not None:
        reasons.append("active authentication signature invalid")
    if ca.status == Status.FAIL and ca_response is not None:
        reasons.append("chip authentication response invalid")

    evidence = None
    try:
        evidence = from_emrtd(mrz, dg11, portrait, {
            "passive_auth": bool(pa["sod_signature"] and pa["dg_hashes"]),
            "csca_trusted": bool(pa["csca_chain"]),
            "active_auth": aa_ok,
            "chip_auth": ca_ok,
        })
    except ValueError as e:
        reasons.append(str(e))
    decision = "accepted" if not reasons and evidence else "rejected"
    return EmrtdResult(sections, mrz.to_dict(), evidence, decision, reasons, facts)
