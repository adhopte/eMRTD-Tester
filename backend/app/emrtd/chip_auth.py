"""ICAO 9303-11 §6.2 Chip Authentication, verified by the *server*.

Plain CA only convinces the terminal that performs it. To let the backend (rather than a
possibly-tampered app) verify the chip, the backend acts as the CA terminal:

1. It generates the ephemeral key pair on the chip's DG14 domain parameters and derives
   the session keys K = KA(sk_terminal, PK_chip) itself.
2. It pre-computes a secure-messaging protected READ BINARY (SSC = 1) under those keys.
3. The phone sends the ephemeral public key to the chip (MSE:Set KAT / General Authenticate)
   and relays the protected command verbatim, returning the raw response.
4. The backend checks the response MAC (SSC = 2) and that the decrypted bytes equal the
   data group content. Only a chip that knows the DG14 private key can derive K, so a
   valid MAC proves the chip is genuine (not a clone of the data groups).
"""
from __future__ import annotations

import hmac
from dataclasses import dataclass

from cryptography.hazmat.primitives.asymmetric import ec

from . import crypto_utils as cu
from . import lds
from .secure_messaging import SmKeys, protect_command, unprotect_response

DEFAULT_CA_OID_ECDH = lds.ID_CA_ECDH_PREFIX + "1"  # id-CA-ECDH-3DES-CBC-CBC
DEFAULT_CA_OID_DH = lds.ID_CA_DH_PREFIX + "1"


class ChipAuthError(ValueError):
    pass


@dataclass
class CaChallenge:
    oid: str
    key_id: int | None
    agreement: str
    cipher: str
    key_len: int
    terminal_public_key: bytes  # EC: uncompressed point; DH: y as big-endian bytes
    shared_secret: bytes
    protected_command: bytes
    read_sfi: int
    read_length: int

    def keys(self) -> SmKeys:
        return SmKeys.derive(self.shared_secret, self.cipher, self.key_len)

    def public_dict(self) -> dict:
        """What the phone needs to perform CA."""
        return {
            "oid": self.oid,
            "key_id": self.key_id,
            "agreement": self.agreement,
            "terminal_public_key": self.terminal_public_key.hex(),
            "protected_command": self.protected_command.hex(),
            "read_sfi": self.read_sfi,
            "read_length": self.read_length,
        }


def prepare(dg14: bytes, read_sfi: int = 0x01, read_length: int = 16, key_id: int | None = None) -> CaChallenge:
    infos = lds.parse_dg14(dg14)
    selected = infos.select_ca(key_id)
    if selected is None:
        raise ChipAuthError("DG14 does not contain a Chip Authentication public key")
    pk_info, ca_info = selected
    chip_key = cu.load_spki(pk_info.spki_der)
    if ca_info is not None:
        oid = ca_info.oid
    else:
        oid = DEFAULT_CA_OID_ECDH if chip_key.kind == "EC" else DEFAULT_CA_OID_DH
    cipher, key_len = lds.ChipAuthInfo(oid, 1, None).cipher

    if chip_key.kind == "EC":
        eph = ec.generate_private_key(chip_key.key.curve)
        shared = eph.exchange(ec.ECDH(), chip_key.key)
        terminal_pub = cu.ec_point_bytes(eph.public_key())
    elif chip_key.kind == "DH":
        x, y = cu.dh_generate(chip_key.dh_params)
        p_len = (chip_key.dh_params.p.bit_length() + 7) // 8
        shared = pow(chip_key.dh_y, x, chip_key.dh_params.p).to_bytes(p_len, "big")
        terminal_pub = y.to_bytes(p_len, "big")
    else:
        raise ChipAuthError(f"unsupported CA key type {chip_key.kind}")

    keys = SmKeys.derive(shared, cipher, key_len)
    # After CA the send sequence counter restarts at 0; the first command uses SSC = 1
    cmd = protect_command(keys, 1, 0x00, 0xB0, 0x80 | read_sfi, 0x00, le=read_length)
    return CaChallenge(
        oid=oid,
        key_id=pk_info.key_id,
        agreement="ECDH" if chip_key.kind == "EC" else "DH",
        cipher=cipher,
        key_len=key_len,
        terminal_public_key=terminal_pub,
        shared_secret=shared,
        protected_command=cmd,
        read_sfi=read_sfi,
        read_length=read_length,
    )


def verify(challenge: CaChallenge, response_apdu: bytes, expected_file: bytes) -> dict:
    """Verify the chip's protected response to the pre-computed READ BINARY (SSC = 2)."""
    try:
        resp = unprotect_response(challenge.keys(), 2, response_apdu)
    except ValueError as e:
        raise ChipAuthError(str(e)) from e
    if resp.sw != 0x9000:
        raise ChipAuthError(f"chip returned SW {resp.sw:04X}")
    expected = expected_file[:challenge.read_length]
    if not hmac.compare_digest(resp.data, expected):
        raise ChipAuthError("data read under CA session keys does not match the submitted data group")
    return {"oid": challenge.oid, "agreement": challenge.agreement, "cipher": f"{challenge.cipher}-{challenge.key_len * 8}",
            "bytes_verified": len(resp.data)}
