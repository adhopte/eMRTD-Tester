"""CSCA trust anchor store.

Loads Country Signing CA certificates from a directory. Supported inputs:
  * individual certificates (.pem, .crt, .cer, .der)
  * ICAO / national CSCA Master Lists (.ml, CMS SignedData containing a CscaMasterList)
"""
from __future__ import annotations

import logging
import pathlib
from dataclasses import dataclass, field

from asn1crypto import cms, core, pem, x509

log = logging.getLogger(__name__)

CSCA_MASTER_LIST_OID = "2.23.136.1.1.2"


class _CertSet(core.SetOf):
    _child_spec = x509.Certificate


class _MasterList(core.Sequence):
    _fields = [("version", core.Integer), ("cert_list", _CertSet)]


def extract_master_list(data: bytes) -> list[x509.Certificate]:
    """Return the CSCA certificates embedded in a CSCA Master List (signature not checked)."""
    ci = cms.ContentInfo.load(data)
    encap = ci["content"]["encap_content_info"]
    if encap["content_type"].dotted != CSCA_MASTER_LIST_OID:
        raise ValueError("not a CSCA master list")
    content = encap["content"]
    raw = content.contents if isinstance(content, core.ParsableOctetString) else content.native
    ml = _MasterList.load(raw)
    return list(ml["cert_list"])


@dataclass
class CscaStore:
    certificates: list[x509.Certificate] = field(default_factory=list)

    @classmethod
    def from_directory(cls, path: str | pathlib.Path) -> "CscaStore":
        store = cls()
        p = pathlib.Path(path)
        if not p.exists():
            return store
        for f in sorted(p.rglob("*")):
            if f.is_file():
                try:
                    store.add_file(f)
                except Exception as e:  # noqa: BLE001 - skip unparsable files, keep loading
                    log.warning("skipping CSCA file %s: %s", f, e)
        log.info("loaded %d CSCA certificates from %s", len(store.certificates), p)
        return store

    def add_file(self, f: pathlib.Path) -> None:
        data = f.read_bytes()
        if f.suffix.lower() in (".ml", ".p7b", ".p7c"):
            for c in extract_master_list(_unarmor(data)):
                self.certificates.append(c)
            return
        if pem.detect(data):
            for _, _, der in pem.unarmor(data, multiple=True):
                self.certificates.append(x509.Certificate.load(der))
        else:
            self.certificates.append(x509.Certificate.load(data))

    def add_der(self, der: bytes) -> None:
        self.certificates.append(x509.Certificate.load(der))

    def candidates_for(self, cert: x509.Certificate) -> list[x509.Certificate]:
        """CSCA certificates whose subject matches the issuer of `cert` (AKI preferred)."""
        aki = cert.authority_key_identifier
        out = []
        for c in self.certificates:
            if aki and c.key_identifier and c.key_identifier != aki:
                continue
            if c.subject.native == cert.issuer.native or (aki and c.key_identifier == aki):
                out.append(c)
        return out

    def __len__(self) -> int:
        return len(self.certificates)


def _unarmor(data: bytes) -> bytes:
    if pem.detect(data):
        _, _, der = pem.unarmor(data)
        return der
    return data
