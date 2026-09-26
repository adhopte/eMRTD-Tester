"""Minimal BER-TLV helpers for ICAO 9303 LDS structures."""
from __future__ import annotations

from dataclasses import dataclass


class TlvError(ValueError):
    pass


@dataclass
class Tlv:
    tag: int
    value: bytes
    raw_len: int  # total encoded length (tag + length + value)


def _read_tag(data: bytes, off: int) -> tuple[int, int]:
    if off >= len(data):
        raise TlvError("unexpected end of data reading tag")
    first = data[off]
    tag = first
    off += 1
    if first & 0x1F == 0x1F:
        while True:
            if off >= len(data):
                raise TlvError("unexpected end of data in multi-byte tag")
            b = data[off]
            tag = (tag << 8) | b
            off += 1
            if not b & 0x80:
                break
    return tag, off


def _read_len(data: bytes, off: int) -> tuple[int, int]:
    if off >= len(data):
        raise TlvError("unexpected end of data reading length")
    first = data[off]
    off += 1
    if first < 0x80:
        return first, off
    n = first & 0x7F
    if n == 0 or n > 4 or off + n > len(data):
        raise TlvError("unsupported or truncated length encoding")
    return int.from_bytes(data[off:off + n], "big"), off + n


def read_tlv(data: bytes, off: int = 0) -> Tlv:
    start = off
    tag, off = _read_tag(data, off)
    length, off = _read_len(data, off)
    if off + length > len(data):
        raise TlvError(f"TLV value for tag {tag:X} truncated")
    return Tlv(tag, data[off:off + length], off + length - start)


def iter_tlv(data: bytes):
    off = 0
    while off < len(data):
        # tolerate trailing padding
        if data[off] in (0x00, 0xFF):
            off += 1
            continue
        t = read_tlv(data, off)
        yield t
        off += t.raw_len


def children_raw(data: bytes) -> list[bytes]:
    """Split concatenated TLVs, returning each element's exact encoding."""
    out, off = [], 0
    while off < len(data):
        t = read_tlv(data, off)
        out.append(data[off:off + t.raw_len])
        off += t.raw_len
    return out


def find(data: bytes, *path: int) -> bytes | None:
    """Follow a path of nested tags, returning the innermost value or None."""
    cur = data
    for tag in path:
        for t in iter_tlv(cur):
            if t.tag == tag:
                cur = t.value
                break
        else:
            return None
    return cur


def unwrap(data: bytes, expected_tag: int) -> bytes:
    t = read_tlv(data)
    if t.tag != expected_tag:
        raise TlvError(f"expected tag {expected_tag:X}, got {t.tag:X}")
    return t.value


def encode_len(n: int) -> bytes:
    if n < 0x80:
        return bytes([n])
    b = n.to_bytes((n.bit_length() + 7) // 8, "big")
    return bytes([0x80 | len(b)]) + b


def encode(tag: int, value: bytes) -> bytes:
    tb = tag.to_bytes((tag.bit_length() + 7) // 8 or 1, "big")
    return tb + encode_len(len(value)) + value
