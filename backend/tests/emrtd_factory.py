"""Builds synthetic, cryptographically valid eMRTDs and simulates the chip for tests."""
from __future__ import annotations

import datetime as dt
import hashlib
import io
import secrets
from dataclasses import dataclass

from asn1crypto import algos, cms, core, keys, x509 as ax509
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
from cryptography.x509.oid import NameOID
from PIL import Image

from app.emrtd import lds, tlv
from app.emrtd.mrz import check_digit
from app.emrtd.secure_messaging import SmKeys, pad, wrap_response


def td3_mrz(surname="ERIKSSON", given="ANNA MARIA", doc_no="L898902C3", nat="UTO", dob="740812",
            sex="F", exp=None, state="UTO") -> str:
    exp = exp or (dt.date.today() + dt.timedelta(days=3 * 365)).strftime("%y%m%d")
    name = (surname.replace(" ", "<") + "<<" + given.replace(" ", "<")).ljust(39, "<")[:39]
    l1 = f"P<{state}{name}"
    doc = doc_no.ljust(9, "<")
    opt = "ZE184226B".ljust(14, "<")
    l2 = f"{doc}{check_digit(doc)}{nat}{dob}{check_digit(dob)}{sex}{exp}{check_digit(exp)}{opt}{check_digit(opt)}"
    composite = l2[0:10] + l2[13:20] + l2[21:43]
    l2 += check_digit(composite)
    assert len(l1) == 44 and len(l2) == 44
    return l1 + "\n" + l2


def td1_mrz(surname="MUSTERMANN", given="ERIKA", doc_no="T22000129", nat="D", dob="830812", sex="F",
            exp=None, state="D") -> str:
    exp = exp or (dt.date.today() + dt.timedelta(days=5 * 365)).strftime("%y%m%d")
    doc = doc_no.ljust(9, "<")
    l1 = f"ID{state.ljust(3, '<')}{doc}{check_digit(doc)}".ljust(30, "<")
    l2 = f"{dob}{check_digit(dob)}{sex}{exp}{check_digit(exp)}{nat.ljust(3, '<')}".ljust(29, "<")
    composite = l1[5:30] + l2[0:7] + l2[8:15] + l2[18:29]
    l2 += check_digit(composite)
    l3 = (surname + "<<" + given.replace(" ", "<")).ljust(30, "<")[:30]
    return "\n".join([l1, l2, l3])


def face_jpeg(size=(240, 320)) -> bytes:
    img = Image.new("RGB", size, (200, 170, 150))
    out = io.BytesIO()
    img.save(out, format="JPEG")
    return out.getvalue()


def make_dg1(mrz: str) -> bytes:
    return tlv.encode(0x61, tlv.encode(0x5F1F, mrz.replace("\n", "").encode()))


def make_dg2(image: bytes) -> bytes:
    # ISO/IEC 19794-5 facial record header (abbreviated but structurally valid)
    header = b"FAC\x00" + b"010\x00" + (14 + 20 + 12 + len(image)).to_bytes(4, "big") + b"\x00\x01"
    facial_info = (20 + 12 + len(image)).to_bytes(4, "big") + b"\x00\x00" + b"\x00" * 14
    image_info = b"\x01\x01" + (240).to_bytes(2, "big") + (320).to_bytes(2, "big") + b"\x00" * 6
    bdb = header + facial_info + image_info + image
    bht = tlv.encode(0xA1, tlv.encode(0x80, b"\x01\x01") + tlv.encode(0x87, b"\x01\x01") + tlv.encode(0x88, b"\x00\x08"))
    inst = tlv.encode(0x7F60, bht + tlv.encode(0x5F2E, bdb))
    return tlv.encode(0x75, tlv.encode(0x7F61, tlv.encode(0x02, b"\x01") + inst))


def make_dg11(full_name="ERIKSSON<<ANNA<MARIA", pob="ZENITH<UTO", dob="19740812") -> bytes:
    tags = tlv.encode(0x5C, bytes.fromhex("5F0E5F115F2B"))
    return tlv.encode(0x6B, tags + tlv.encode(0x5F0E, full_name.encode()) + tlv.encode(0x5F11, pob.encode())
                      + tlv.encode(0x5F2B, dob.encode()))


def _spki(pub) -> bytes:
    return pub.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def make_dg14(ca_pub: ec.EllipticCurvePublicKey, ca_oid_suffix="1", key_id: int | None = None,
              aa_ecdsa_oid: str | None = None) -> bytes:
    infos = []
    ca_info = [core.ObjectIdentifier(lds.ID_CA_ECDH_PREFIX + ca_oid_suffix), core.Integer(1)]
    pk_info = [core.ObjectIdentifier(lds.ID_PK_ECDH), core.Any.load(_spki(ca_pub))]
    if key_id is not None:
        ca_info.append(core.Integer(key_id))
        pk_info.append(core.Integer(key_id))
    infos.append(b"".join(x.dump() for x in ca_info))
    infos.append(b"".join(x.dump() for x in pk_info))
    if aa_ecdsa_oid:
        infos.append(core.ObjectIdentifier(lds.ID_AA).dump() + core.Integer(1).dump()
                     + core.ObjectIdentifier(aa_ecdsa_oid).dump())
    body = b"".join(tlv.encode(0x30, i) for i in infos)
    return tlv.encode(0x6E, tlv.encode(0x31, body))


def make_dg15(aa_pub) -> bytes:
    return tlv.encode(0x6F, _spki(aa_pub))


@dataclass
class TestPki:
    csca_key: ec.EllipticCurvePrivateKey
    csca_cert: x509.Certificate
    dsc_key: object
    dsc_cert: x509.Certificate


def make_pki(country="UT", dsc_rsa=False) -> TestPki:
    now = dt.datetime.now(dt.timezone.utc)
    csca_key = ec.generate_private_key(ec.BrainpoolP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COUNTRY_NAME, country),
                      x509.NameAttribute(NameOID.COMMON_NAME, "Test CSCA")])
    csca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(csca_key.public_key())
            .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=3650))
            .add_extension(x509.BasicConstraints(True, 0), True)
            .add_extension(x509.SubjectKeyIdentifier.from_public_key(csca_key.public_key()), False)
            .sign(csca_key, hashes.SHA256()))
    dsc_key = rsa.generate_private_key(65537, 2048) if dsc_rsa else ec.generate_private_key(ec.SECP256R1())
    dsc = (x509.CertificateBuilder()
           .subject_name(x509.Name([x509.NameAttribute(NameOID.COUNTRY_NAME, country),
                                    x509.NameAttribute(NameOID.COMMON_NAME, "Test DS")]))
           .issuer_name(name).public_key(dsc_key.public_key())
           .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=1))
           .not_valid_after(now + dt.timedelta(days=365))
           .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(csca_key.public_key()), False)
           .sign(csca_key, hashes.SHA256()))
    return TestPki(csca_key, csca, dsc_key, dsc)


def make_sod(dgs: dict[int, bytes], pki: TestPki, hash_algo="sha256") -> bytes:
    lso = lds.LdsSecurityObject({
        "version": 0,
        "hash_algorithm": {"algorithm": hash_algo},
        "data_group_hash_values": [
            {"data_group_number": n, "data_group_hash_value": hashlib.new(hash_algo, d).digest()}
            for n, d in sorted(dgs.items())],
    })
    econtent = lso.dump()
    signed_attrs = cms.CMSAttributes([
        {"type": "content_type", "values": [lds.LDS_SECURITY_OBJECT_OID]},
        {"type": "message_digest", "values": [hashlib.new(hash_algo, econtent).digest()]},
    ])
    to_sign = signed_attrs.dump()
    h = {"sha256": hashes.SHA256(), "sha1": hashes.SHA1()}[hash_algo]
    if isinstance(pki.dsc_key, rsa.RSAPrivateKey):
        sig = pki.dsc_key.sign(to_sign, padding.PKCS1v15(), h)
        sig_algo = {"algorithm": f"{hash_algo}_rsa"}
    else:
        sig = pki.dsc_key.sign(to_sign, ec.ECDSA(h))
        sig_algo = {"algorithm": f"{hash_algo}_ecdsa"}
    dsc_a = ax509.Certificate.load(pki.dsc_cert.public_bytes(serialization.Encoding.DER))
    signer_info = cms.SignerInfo({
        "version": "v1",
        "sid": cms.SignerIdentifier({"issuer_and_serial_number": {
            "issuer": dsc_a.issuer, "serial_number": dsc_a.serial_number}}),
        "digest_algorithm": {"algorithm": hash_algo},
        "signed_attrs": signed_attrs,
        "signature_algorithm": sig_algo,
        "signature": sig,
    })
    sd = cms.SignedData({
        "version": "v3",
        "digest_algorithms": [{"algorithm": hash_algo}],
        "encap_content_info": {"content_type": lds.LDS_SECURITY_OBJECT_OID,
                               "content": core.ParsableOctetString(econtent)},
        "certificates": [dsc_a],
        "signer_infos": [signer_info],
    })
    ci = cms.ContentInfo({"content_type": "signed_data", "content": sd})
    return tlv.encode(0x77, ci.dump())


def sign_9796_2(key: rsa.RSAPrivateKey, challenge: bytes, hash_name="sha1") -> bytes:
    """ISO/IEC 9796-2 DS1 partial recovery signing, as done by AA chips."""
    n = key.public_key().public_numbers().n
    d = key.private_numbers().d
    k = (n.bit_length() + 7) // 8
    h_len = hashlib.new(hash_name).digest_size
    trailer = b"\xBC" if hash_name == "sha1" else bytes([{"sha256": 0x34, "sha384": 0x36, "sha512": 0x35}[hash_name], 0xCC])
    m1 = secrets.token_bytes(k - h_len - 1 - len(trailer))
    f = b"\x6A" + m1 + hashlib.new(hash_name, m1 + challenge).digest() + trailer
    s = pow(int.from_bytes(f, "big"), d, n)
    s = min(s, n - s)
    return s.to_bytes(k, "big")


@dataclass
class VirtualPassport:
    pki: TestPki
    dgs: dict[int, bytes]
    sod: bytes
    aa_key: object | None
    ca_key: ec.EllipticCurvePrivateKey | None
    mrz: str

    def internal_authenticate(self, challenge: bytes) -> bytes:
        if isinstance(self.aa_key, rsa.RSAPrivateKey):
            return sign_9796_2(self.aa_key, challenge)
        der = self.aa_key.sign(challenge, ec.ECDSA(hashes.SHA256()))
        from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature

        r, s = decode_dss_signature(der)
        size = (self.aa_key.curve.key_size + 7) // 8
        return r.to_bytes(size, "big") + s.to_bytes(size, "big")

    def chip_authenticate_and_transmit(self, ca: dict, cipher: str, key_len: int) -> bytes:
        """Receive the terminal key, derive session keys, then process one protected READ BINARY."""
        terminal = ec.EllipticCurvePublicKey.from_encoded_point(self.ca_key.curve,
                                                                bytes.fromhex(ca["terminal_public_key"]))
        shared = self.ca_key.exchange(ec.ECDH(), terminal)
        keys_ = SmKeys.derive(shared, cipher, key_len)
        cmd = bytes.fromhex(ca["protected_command"])
        # verify command MAC like a real chip (SSC = 1)
        header, body = cmd[:4], cmd[5:-1]
        parsed = tlv.children_raw(body)
        do97 = parsed[0]
        mac = tlv.read_tlv(parsed[1]).value
        assert keys_.mac(1, pad(pad(header, keys_.block) + do97, keys_.block)) == mac, "command MAC invalid"
        le = tlv.read_tlv(do97).value[0]
        sfi = header[2] & 0x1F
        return wrap_response(keys_, 2, self.dgs[sfi][:le])


def make_passport(*, aa="rsa", ca=True, pki: TestPki | None = None, mrz: str | None = None,
                  ca_cipher_suffix="1") -> VirtualPassport:
    pki = pki or make_pki()
    mrz = mrz or td3_mrz()
    dgs = {1: make_dg1(mrz), 2: make_dg2(face_jpeg()), 11: make_dg11()}
    ca_key = None
    aa_key = None
    if ca:
        ca_key = ec.generate_private_key(ec.BrainpoolP256R1())
    if aa == "rsa":
        aa_key = rsa.generate_private_key(65537, 1024)
    elif aa == "ec":
        aa_key = ec.generate_private_key(ec.SECP256R1())
    if ca or aa == "ec":
        if ca:
            dgs[14] = make_dg14(ca_key.public_key(), ca_cipher_suffix,
                                aa_ecdsa_oid="1.2.840.10045.4.3.2" if aa == "ec" else None)
    if aa_key is not None:
        dgs[15] = make_dg15(aa_key.public_key())
    return VirtualPassport(pki, dgs, make_sod(dgs, pki), aa_key, ca_key, mrz)
