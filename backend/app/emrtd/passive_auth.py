"""ICAO 9303-11 §5.1 Passive Authentication."""
from __future__ import annotations

import datetime as dt
import hmac

from asn1crypto import cms, x509

from ..report import Section
from . import crypto_utils as cu
from .csca_store import CscaStore
from .lds import Sod


def _signer_cert(sod: Sod) -> x509.Certificate | None:
    si = sod.signed_data["signer_infos"][0]
    sid = si["sid"]
    certs = [x509.Certificate.load(c) for c in sod.certificates]
    for c in certs:
        if sid.name == "issuer_and_serial_number":
            if (c.issuer.native == sid.chosen["issuer"].native
                    and c.serial_number == sid.chosen["serial_number"].native):
                return c
        elif sid.name == "subject_key_identifier" and c.key_identifier == sid.chosen.native:
            return c
    return certs[0] if certs else None


def _verify_signer_info(sod: Sod, dsc: x509.Certificate) -> None:
    si: cms.SignerInfo = sod.signed_data["signer_infos"][0]
    digest_algo = si["digest_algorithm"]["algorithm"].native
    signed_attrs = si["signed_attrs"]
    if signed_attrs and signed_attrs.native is not None and len(signed_attrs):
        md = None
        ctype = None
        for attr in signed_attrs:
            if attr["type"].native == "message_digest":
                md = attr["values"][0].native
            elif attr["type"].native == "content_type":
                ctype = attr["values"][0].dotted
        if md is None:
            raise ValueError("signedAttrs lack messageDigest")
        if ctype is not None and ctype != "2.23.136.1.1.1":
            raise ValueError(f"signedAttrs contentType mismatch ({ctype})")
        if not hmac.compare_digest(md, cu.digest(digest_algo, sod.econtent_bytes)):
            raise ValueError("messageDigest does not match eContent")
        # Signature is computed over the DER encoding of signedAttrs with a SET OF tag
        to_verify = b"\x31" + signed_attrs.dump()[1:]
    else:
        to_verify = sod.econtent_bytes
    pub = cu.load_certificate_public_key(dsc.dump())
    # Some SODs use a bare rsaEncryption OID; the hash then comes from digestAlgorithm
    cu.verify_signature(pub, si["signature"].native, to_verify, si["signature_algorithm"],
                        fallback_hash=digest_algo)


def verify_certificate_signature(cert: x509.Certificate, issuer: x509.Certificate) -> None:
    pub = cu.load_certificate_public_key(issuer.dump())
    cu.verify_signature(pub, cert["signature_value"].native, cert["tbs_certificate"].dump(),
                        cert["signature_algorithm"])


def _in_validity(cert: x509.Certificate, at: dt.datetime) -> bool:
    v = cert["tbs_certificate"]["validity"]
    return v["not_before"].native <= at <= v["not_after"].native


def passive_authentication(
    sod: Sod,
    data_groups: dict[int, bytes],
    csca_store: CscaStore,
    now: dt.datetime | None = None,
) -> tuple[Section, dict]:
    """Run PA. Returns the report section and facts used by the issuance policy."""
    now = now or dt.datetime.now(dt.timezone.utc)
    sec = Section("passive_authentication")
    facts = {"sod_signature": False, "dg_hashes": False, "csca_chain": False, "dsc_subject": None}

    # 1. Data group hashes
    algo = sod.hash_algorithm
    expected = sod.dg_hashes
    all_ok = True
    for num, content in sorted(data_groups.items()):
        if num not in expected:
            sec.failed(f"dg{num}_hash", "data group not listed in EF.SOD")
            all_ok = False
            continue
        ok = hmac.compare_digest(cu.digest(algo, content), expected[num])
        (sec.passed if ok else sec.failed)(f"dg{num}_hash", f"{algo} hash {'matches' if ok else 'MISMATCH'}")
        all_ok &= ok
    facts["dg_hashes"] = all_ok and bool(data_groups)
    facts["dg_numbers_in_sod"] = sorted(expected)

    # 2. SOD signature by the Document Signer
    dsc = _signer_cert(sod)
    if dsc is None:
        sec.failed("document_signer_certificate", "no DSC embedded in EF.SOD")
        return sec, facts
    facts["dsc_subject"] = dsc.subject.human_friendly
    try:
        _verify_signer_info(sod, dsc)
        facts["sod_signature"] = True
        sec.passed("sod_signature", f"signed by {dsc.subject.human_friendly}")
    except Exception as e:  # noqa: BLE001
        sec.failed("sod_signature", f"signature invalid: {e}")

    if _in_validity(dsc, now):
        sec.passed("dsc_validity", "DSC within validity period")
    else:
        # A DSC may legitimately expire before the document; ICAO treats this as informative
        sec.warn("dsc_validity", "DSC outside its validity period at verification time")

    # 3. DSC -> CSCA chain
    if not len(csca_store):
        sec.warn("csca_chain", "no CSCA trust anchors configured; chain not verified")
        return sec, facts
    candidates = csca_store.candidates_for(dsc)
    if not candidates:
        sec.failed("csca_chain", f"no CSCA found for issuer {dsc.issuer.human_friendly}")
        return sec, facts
    errors = []
    for csca in candidates:
        try:
            verify_certificate_signature(dsc, csca)
            facts["csca_chain"] = True
            sec.passed("csca_chain", f"DSC issued by trusted CSCA {csca.subject.human_friendly}")
            if not _in_validity(csca, now):
                sec.warn("csca_validity", "CSCA outside its validity period")
            break
        except Exception as e:  # noqa: BLE001
            errors.append(str(e))
    else:
        sec.failed("csca_chain", "DSC signature not verifiable by any matching CSCA: " + "; ".join(errors))
    return sec, facts
