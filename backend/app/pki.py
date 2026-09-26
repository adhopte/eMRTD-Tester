"""Dummy "citizen PKI" for the PID issuer, following ISO/IEC 18013-5 Annex B profiles.

* IACA root certificate  (Table B.1)  – the trust anchor verifiers must import
* Document Signer (DS)   (Table B.3)  – signs the MSO of every issued PID

Keys and certificates are generated on first start and persisted in `pki_dir`.
THIS IS A TEST PKI. Never use it for production credentials.
"""
from __future__ import annotations

import datetime as dt
import logging
import os
import pathlib
from dataclasses import dataclass

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID, ObjectIdentifier

log = logging.getLogger(__name__)

MDL_DS_EKU = ObjectIdentifier("1.0.18013.5.1.2")


@dataclass
class IssuerPki:
    iaca_key: ec.EllipticCurvePrivateKey
    iaca_cert: x509.Certificate
    ds_key: ec.EllipticCurvePrivateKey
    ds_cert: x509.Certificate

    @property
    def country(self) -> str:
        return self.ds_cert.subject.get_attributes_for_oid(NameOID.COUNTRY_NAME)[0].value

    def iaca_pem(self) -> bytes:
        return self.iaca_cert.public_bytes(serialization.Encoding.PEM)

    def ds_pem(self) -> bytes:
        return self.ds_cert.public_bytes(serialization.Encoding.PEM)

    def ds_der(self) -> bytes:
        return self.ds_cert.public_bytes(serialization.Encoding.DER)

    def crl_der(self) -> bytes:
        now = dt.datetime.now(dt.timezone.utc)
        crl = (
            x509.CertificateRevocationListBuilder()
            .issuer_name(self.iaca_cert.subject)
            .last_update(now)
            .next_update(now + dt.timedelta(days=30))
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(self.iaca_key.public_key()), False)
            .add_extension(x509.CRLNumber(int(now.timestamp())), False)
            .sign(self.iaca_key, hashes.SHA256())
        )
        return crl.public_bytes(serialization.Encoding.DER)


def _name(country: str, org: str, cn: str) -> x509.Name:
    return x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, country),
        x509.NameAttribute(NameOID.ORGANIZATION_NAME, org),
        x509.NameAttribute(NameOID.COMMON_NAME, cn),
    ])


def generate(country: str, organization: str, public_base_url: str) -> IssuerPki:
    now = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    base = public_base_url.rstrip("/")
    crl_dp = x509.CRLDistributionPoints([x509.DistributionPoint(
        full_name=[x509.UniformResourceIdentifier(f"{base}/pki/crl.der")],
        relative_name=None, reasons=None, crl_issuer=None)])
    ian = x509.IssuerAlternativeName([x509.UniformResourceIdentifier(base)])

    iaca_key = ec.generate_private_key(ec.SECP256R1())
    iaca_name = _name(country, organization, f"{organization} IACA (TEST)")
    iaca_cert = (
        x509.CertificateBuilder()
        .subject_name(iaca_name)
        .issuer_name(iaca_name)
        .public_key(iaca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + dt.timedelta(days=365 * 9))
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(iaca_key.public_key()), False)
        .add_extension(x509.KeyUsage(False, False, False, False, False, True, True, False, False), True)
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
        .add_extension(ian, False)
        .add_extension(crl_dp, False)
        .sign(iaca_key, hashes.SHA256())
    )

    ds_key = ec.generate_private_key(ec.SECP256R1())
    ds_cert = (
        x509.CertificateBuilder()
        .subject_name(_name(country, organization, f"{organization} PID Document Signer (TEST)"))
        .issuer_name(iaca_name)
        .public_key(ds_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now)
        .not_valid_after(now + dt.timedelta(days=450))
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(iaca_key.public_key()), False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ds_key.public_key()), False)
        .add_extension(x509.KeyUsage(True, False, False, False, False, False, False, False, False), True)
        .add_extension(x509.ExtendedKeyUsage([MDL_DS_EKU]), True)
        .add_extension(ian, False)
        .add_extension(crl_dp, False)
        .sign(iaca_key, hashes.SHA256())
    )
    return IssuerPki(iaca_key, iaca_cert, ds_key, ds_cert)


def _write_key(path: pathlib.Path, key: ec.EllipticCurvePrivateKey) -> None:
    path.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))
    os.chmod(path, 0o600)


def load_or_create(pki_dir: str | pathlib.Path, country: str, organization: str, public_base_url: str) -> IssuerPki:
    d = pathlib.Path(pki_dir)
    files = {n: d / n for n in ("iaca.key", "iaca.pem", "ds.key", "ds.pem")}
    if all(f.exists() for f in files.values()):
        pki = IssuerPki(
            serialization.load_pem_private_key(files["iaca.key"].read_bytes(), None),
            x509.load_pem_x509_certificate(files["iaca.pem"].read_bytes()),
            serialization.load_pem_private_key(files["ds.key"].read_bytes(), None),
            x509.load_pem_x509_certificate(files["ds.pem"].read_bytes()),
        )
        if pki.ds_cert.not_valid_after_utc > dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=30):
            return pki
        log.warning("Document Signer certificate close to expiry; regenerating test PKI")
    pki = generate(country, organization, public_base_url)
    try:
        d.mkdir(parents=True, exist_ok=True)
        _write_key(files["iaca.key"], pki.iaca_key)
        files["iaca.pem"].write_bytes(pki.iaca_pem())
        _write_key(files["ds.key"], pki.ds_key)
        files["ds.pem"].write_bytes(pki.ds_pem())
        log.info("generated new test IACA + Document Signer in %s", d)
    except OSError as e:
        # e.g. a read-only secrets mount without the PKI files yet
        log.warning("could not persist the generated PKI to %s (%s); it will change on restart, "
                    "which invalidates previously issued PIDs", d, e)
    return pki
