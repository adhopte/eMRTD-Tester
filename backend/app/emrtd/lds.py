"""Parsers for ICAO 9303-10 LDS1 elementary files."""
from __future__ import annotations

import io
from dataclasses import dataclass, field

from asn1crypto import algos, cms, core, keys

from . import mrz as mrz_mod
from . import tlv

# ---------------------------------------------------------------------------
# EF.SOD
# ---------------------------------------------------------------------------


class DataGroupHash(core.Sequence):
    _fields = [("data_group_number", core.Integer), ("data_group_hash_value", core.OctetString)]


class DataGroupHashes(core.SequenceOf):
    _child_spec = DataGroupHash


class LdsVersionInfo(core.Sequence):
    _fields = [("lds_version", core.PrintableString), ("unicode_version", core.PrintableString)]


class LdsSecurityObject(core.Sequence):
    _fields = [
        ("version", core.Integer),
        ("hash_algorithm", algos.DigestAlgorithm),
        ("data_group_hash_values", DataGroupHashes),
        ("lds_version_info", LdsVersionInfo, {"optional": True}),
    ]


LDS_SECURITY_OBJECT_OID = "2.23.136.1.1.1"
cms.ContentType._map[LDS_SECURITY_OBJECT_OID] = "lds_security_object"
cms.EncapsulatedContentInfo._oid_specs["lds_security_object"] = LdsSecurityObject


@dataclass
class Sod:
    signed_data: cms.SignedData
    lds: LdsSecurityObject
    econtent_bytes: bytes

    @property
    def hash_algorithm(self) -> str:
        return self.lds["hash_algorithm"]["algorithm"].native

    @property
    def dg_hashes(self) -> dict[int, bytes]:
        return {h["data_group_number"].native: h["data_group_hash_value"].native
                for h in self.lds["data_group_hash_values"]}

    @property
    def certificates(self) -> list[bytes]:
        certs = self.signed_data["certificates"]
        if not certs or certs.native is None:
            return []
        return [c.chosen.dump() for c in certs if c.name == "certificate"]


def parse_sod(data: bytes) -> Sod:
    body = tlv.unwrap(data, 0x77) if data[:1] == b"\x77" else data
    ci = cms.ContentInfo.load(body)
    if ci["content_type"].native != "signed_data":
        raise ValueError("EF.SOD does not contain CMS SignedData")
    sd = ci["content"]
    encap = sd["encap_content_info"]
    ctype = encap["content_type"].dotted
    if ctype != LDS_SECURITY_OBJECT_OID:
        raise ValueError(f"unexpected SOD eContentType {ctype}")
    econtent = encap["content"]
    raw = econtent.contents if isinstance(econtent, core.ParsableOctetString) else econtent.native
    lds = LdsSecurityObject.load(raw)
    return Sod(sd, lds, raw)


# ---------------------------------------------------------------------------
# DG1 / DG2 / DG11 / DG12
# ---------------------------------------------------------------------------


def parse_dg1(data: bytes) -> mrz_mod.Mrz:
    mrz_bytes = tlv.find(data, 0x61, 0x5F1F)
    if mrz_bytes is None:
        raise ValueError("DG1 does not contain an MRZ (5F1F)")
    return mrz_mod.parse(mrz_bytes.decode("ascii", "replace"))


@dataclass
class FaceImage:
    mime_type: str
    data: bytes


def parse_dg2(data: bytes) -> list[FaceImage]:
    """Extract facial images from DG2 (ISO/IEC 19794-5 or ISO/IEC 39794-5 blocks)."""
    images: list[FaceImage] = []
    group = tlv.find(data, 0x75, 0x7F61)
    if group is None:
        return images
    for t in tlv.iter_tlv(group):
        if t.tag != 0x7F60:
            continue
        for inner in tlv.iter_tlv(t.value):
            if inner.tag in (0x5F2E, 0x7F2E):
                img = _extract_image(inner.value)
                if img:
                    images.append(img)
    return images


def _extract_image(block: bytes) -> FaceImage | None:
    jpeg = block.find(b"\xFF\xD8\xFF")
    jp2 = block.find(b"\x00\x00\x00\x0C\x6A\x50\x20\x20")
    j2k = block.find(b"\xFF\x4F\xFF\x51")
    candidates = [(i, m) for i, m in ((jpeg, "image/jpeg"), (jp2, "image/jp2"), (j2k, "image/j2k")) if i >= 0]
    if not candidates:
        return None
    off, mime = min(candidates)
    return FaceImage(mime, block[off:])


def face_as_jpeg(face: FaceImage, max_side: int = 640) -> bytes:
    """ISO 18013-5 portraits must be JPEG; transcode JPEG2000 when required."""
    from PIL import Image

    if face.mime_type == "image/jpeg":
        return face.data
    img = Image.open(io.BytesIO(face.data)).convert("RGB")
    img.thumbnail((max_side, max_side))
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=90)
    return out.getvalue()


@dataclass
class Dg11:
    full_name: str | None = None
    other_names: list[str] = field(default_factory=list)
    personal_number: str | None = None
    full_date_of_birth: str | None = None
    place_of_birth: list[str] = field(default_factory=list)
    permanent_address: list[str] = field(default_factory=list)
    telephone: str | None = None
    profession: str | None = None
    title: str | None = None


def _txt(b: bytes) -> str:
    try:
        return b.decode("utf-8").strip()
    except UnicodeDecodeError:
        return b.decode("latin-1").strip()


def parse_dg11(data: bytes) -> Dg11:
    body = tlv.unwrap(data, 0x6B)
    out = Dg11()
    for t in tlv.iter_tlv(body):
        v = t.value
        if t.tag == 0x5F0E:
            out.full_name = _txt(v)
        elif t.tag == 0xA0:
            out.other_names = [_txt(x.value) for x in tlv.iter_tlv(v) if x.tag == 0x5F0F]
        elif t.tag == 0x5F0F:
            out.other_names.append(_txt(v))
        elif t.tag == 0x5F10:
            out.personal_number = _txt(v)
        elif t.tag == 0x5F2B:
            out.full_date_of_birth = _txt(v) if len(v) != 4 else v.hex()
        elif t.tag == 0x5F11:
            out.place_of_birth = [p for p in _txt(v).split("<") if p]
        elif t.tag == 0x5F42:
            out.permanent_address = [p for p in _txt(v).split("<") if p]
        elif t.tag == 0x5F12:
            out.telephone = _txt(v)
        elif t.tag == 0x5F13:
            out.profession = _txt(v)
        elif t.tag == 0x5F14:
            out.title = _txt(v)
    return out


# ---------------------------------------------------------------------------
# DG14 (SecurityInfos) and DG15 (Active Authentication public key)
# ---------------------------------------------------------------------------

ID_PK_DH = "0.4.0.127.0.7.2.2.1.1"
ID_PK_ECDH = "0.4.0.127.0.7.2.2.1.2"
ID_CA_DH_PREFIX = "0.4.0.127.0.7.2.2.3.1."
ID_CA_ECDH_PREFIX = "0.4.0.127.0.7.2.2.3.2."
ID_AA = "2.23.136.1.1.5"
ID_PACE_PREFIX = "0.4.0.127.0.7.2.2.4."
ID_TA = "0.4.0.127.0.7.2.2.2"

CA_CIPHERS = {"1": ("3DES", 16), "2": ("AES", 16), "3": ("AES", 24), "4": ("AES", 32)}


@dataclass
class ChipAuthInfo:
    oid: str
    version: int
    key_id: int | None

    @property
    def agreement(self) -> str:
        return "ECDH" if self.oid.startswith(ID_CA_ECDH_PREFIX) else "DH"

    @property
    def cipher(self) -> tuple[str, int]:
        return CA_CIPHERS[self.oid.rsplit(".", 1)[1]]


@dataclass
class ChipAuthPublicKeyInfo:
    oid: str
    spki_der: bytes
    key_id: int | None


@dataclass
class SecurityInfos:
    ca_infos: list[ChipAuthInfo] = field(default_factory=list)
    ca_public_keys: list[ChipAuthPublicKeyInfo] = field(default_factory=list)
    aa_signature_algorithm: str | None = None  # dotted OID of the AA ECDSA signature algorithm
    protocols: list[str] = field(default_factory=list)

    def select_ca(self, key_id: int | None = None) -> tuple[ChipAuthPublicKeyInfo, ChipAuthInfo | None] | None:
        keys_ = [k for k in self.ca_public_keys if key_id is None or k.key_id == key_id]
        if not keys_:
            return None
        pk = keys_[0]
        infos = [i for i in self.ca_infos if i.key_id in (None, pk.key_id)]
        return pk, (infos[0] if infos else None)


def parse_security_infos(data: bytes) -> SecurityInfos:
    """Parse a SecurityInfos SET (DG14 body or EF.CardAccess)."""
    out = SecurityInfos()
    body = tlv.unwrap(data, 0x31)
    for t in tlv.iter_tlv(body):
        if t.tag != 0x30:
            continue
        children = tlv.children_raw(t.value)
        oid = core.ObjectIdentifier.load(children[0]).dotted
        out.protocols.append(oid)
        if oid.startswith(ID_CA_DH_PREFIX) or oid.startswith(ID_CA_ECDH_PREFIX):
            version = core.Integer.load(children[1]).native
            key_id = core.Integer.load(children[2]).native if len(children) > 2 else None
            out.ca_infos.append(ChipAuthInfo(oid, version, key_id))
        elif oid in (ID_PK_DH, ID_PK_ECDH):
            key_id = core.Integer.load(children[2]).native if len(children) > 2 else None
            out.ca_public_keys.append(ChipAuthPublicKeyInfo(oid, children[1], key_id))
        elif oid == ID_AA and len(children) > 2:
            out.aa_signature_algorithm = core.ObjectIdentifier.load(children[2]).dotted
    return out


def parse_dg14(data: bytes) -> SecurityInfos:
    return parse_security_infos(tlv.unwrap(data, 0x6E))


def parse_dg15(data: bytes) -> bytes:
    """Return the SubjectPublicKeyInfo DER of the Active Authentication key."""
    return tlv.unwrap(data, 0x6F)


DG_TAGS = {1: 0x61, 2: 0x75, 3: 0x63, 4: 0x76, 5: 0x65, 6: 0x66, 7: 0x67, 8: 0x68, 9: 0x69,
           10: 0x6A, 11: 0x6B, 12: 0x6C, 13: 0x6D, 14: 0x6E, 15: 0x6F, 16: 0x70}

__all__ = [
    "Sod", "parse_sod", "parse_dg1", "parse_dg2", "parse_dg11", "parse_dg14", "parse_dg15",
    "parse_security_infos", "face_as_jpeg", "SecurityInfos", "ChipAuthInfo", "ChipAuthPublicKeyInfo",
    "LdsSecurityObject", "DG_TAGS", "keys",
]
