"""ICAO 9303-11 §9.8 secure messaging (3DES and AES) — just enough to build a protected
command and verify/decrypt the chip's protected response on the server."""
from __future__ import annotations

import hmac
from dataclasses import dataclass

from cryptography.hazmat.primitives import cmac
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

try:  # cryptography >= 43 moved TripleDES to the "decrepit" module
    from cryptography.hazmat.decrepit.ciphers.algorithms import TripleDES
except ImportError:  # pragma: no cover
    TripleDES = algorithms.TripleDES

from . import crypto_utils as cu
from . import tlv


def kdf(shared_secret: bytes, counter: int, cipher: str, key_len: int) -> bytes:
    """ICAO 9303-11 §9.7.1 key derivation."""
    data = shared_secret + counter.to_bytes(4, "big")
    if cipher == "3DES":
        return cu.digest("sha1", data)[:16]
    if key_len == 16:
        return cu.digest("sha1", data)[:16]
    return cu.digest("sha256", data)[:key_len]


def pad(data: bytes, block: int) -> bytes:
    data = data + b"\x80"
    return data + b"\x00" * (-len(data) % block)


def unpad(data: bytes) -> bytes:
    i = data.rstrip(b"\x00")
    if not i.endswith(b"\x80"):
        raise ValueError("bad ISO 9797-1 padding")
    return i[:-1]


def _des(key8: bytes) -> TripleDES:
    return TripleDES(key8 * 3)


def retail_mac(key16: bytes, data: bytes) -> bytes:
    """ISO/IEC 9797-1 MAC algorithm 3 with DES; `data` must already be padded."""
    k1, k2 = key16[:8], key16[8:16]
    enc = Cipher(_des(k1), modes.CBC(b"\x00" * 8)).encryptor()
    h = enc.update(data)[-8:]
    h = Cipher(_des(k2), modes.ECB()).decryptor().update(h)
    return Cipher(_des(k1), modes.ECB()).encryptor().update(h)


@dataclass
class SmKeys:
    cipher: str  # "3DES" or "AES"
    ks_enc: bytes
    ks_mac: bytes

    @property
    def block(self) -> int:
        return 8 if self.cipher == "3DES" else 16

    def ssc_bytes(self, ssc: int) -> bytes:
        return ssc.to_bytes(self.block, "big")

    def mac(self, ssc: int, data_padded: bytes) -> bytes:
        msg = self.ssc_bytes(ssc) + data_padded
        if self.cipher == "3DES":
            return retail_mac(self.ks_mac, msg)
        c = cmac.CMAC(algorithms.AES(self.ks_mac))
        c.update(msg)
        return c.finalize()[:8]

    def _cipher(self, ssc: int) -> Cipher:
        if self.cipher == "3DES":
            return Cipher(TripleDES(self.ks_enc + self.ks_enc[:8]), modes.CBC(b"\x00" * 8))
        iv = Cipher(algorithms.AES(self.ks_enc), modes.ECB()).encryptor().update(self.ssc_bytes(ssc))
        return Cipher(algorithms.AES(self.ks_enc), modes.CBC(iv))

    def encrypt(self, ssc: int, data: bytes) -> bytes:
        e = self._cipher(ssc).encryptor()
        return e.update(pad(data, self.block)) + e.finalize()

    def decrypt(self, ssc: int, data: bytes) -> bytes:
        d = self._cipher(ssc).decryptor()
        return unpad(d.update(data) + d.finalize())

    @classmethod
    def derive(cls, shared_secret: bytes, cipher: str, key_len: int) -> "SmKeys":
        return cls(cipher, kdf(shared_secret, 1, cipher, key_len), kdf(shared_secret, 2, cipher, key_len))


def protect_command(keys: SmKeys, ssc: int, cla: int, ins: int, p1: int, p2: int,
                    data: bytes = b"", le: int | None = None) -> bytes:
    """Build a secure-messaging protected short APDU."""
    cla |= 0x0C
    header = bytes([cla, ins, p1, p2])
    do87 = b""
    if data:
        do87 = tlv.encode(0x87, b"\x01" + keys.encrypt(ssc, data))
    do97 = tlv.encode(0x97, bytes([le & 0xFF])) if le is not None else b""
    mac = keys.mac(ssc, pad(pad(header, keys.block) + do87 + do97, keys.block))
    body = do87 + do97 + tlv.encode(0x8E, mac)
    return header + bytes([len(body)]) + body + b"\x00"


@dataclass
class UnprotectedResponse:
    data: bytes
    sw: int


def unprotect_response(keys: SmKeys, ssc: int, apdu: bytes) -> UnprotectedResponse:
    """Verify the DO8E MAC of a protected response and decrypt DO87. Raises on MAC failure."""
    if len(apdu) < 2:
        raise ValueError("response too short")
    body, sw = apdu[:-2], int.from_bytes(apdu[-2:], "big")
    do87 = do99 = mac = None
    mac_input = b""
    for raw in tlv.children_raw(body):
        t = tlv.read_tlv(raw)
        if t.tag == 0x87:
            do87 = t.value
            mac_input += raw
        elif t.tag == 0x85:  # odd-INS plain data object, not expected here
            mac_input += raw
        elif t.tag == 0x99:
            do99 = t.value
            mac_input += raw
        elif t.tag == 0x8E:
            mac = t.value
    if mac is None:
        raise ValueError(f"response is not SM-protected (SW={sw:04X})")
    expected = keys.mac(ssc, pad(mac_input, keys.block))
    if not hmac.compare_digest(mac, expected):
        raise ValueError("secure messaging MAC verification failed")
    data = b""
    if do87 is not None:
        if do87[0] != 0x01:
            raise ValueError("unexpected DO87 padding indicator")
        data = keys.decrypt(ssc, do87[1:])
    inner_sw = int.from_bytes(do99, "big") if do99 else sw
    return UnprotectedResponse(data, inner_sw)


def wrap_response(keys: SmKeys, ssc: int, data: bytes, sw: int = 0x9000) -> bytes:
    """Build a protected response (used by the chip simulator in tests)."""
    do87 = tlv.encode(0x87, b"\x01" + keys.encrypt(ssc, data)) if data else b""
    do99 = tlv.encode(0x99, sw.to_bytes(2, "big"))
    mac = keys.mac(ssc, pad(do87 + do99, keys.block))
    return do87 + do99 + tlv.encode(0x8E, mac) + sw.to_bytes(2, "big")
