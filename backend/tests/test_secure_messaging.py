"""ICAO 9303-11 Appendix D.4 worked example (3DES secure messaging)."""
from app.emrtd.secure_messaging import SmKeys, protect_command, unprotect_response

KEYS = SmKeys("3DES", bytes.fromhex("979EC13B1CBFE9DCD01AB0FED307EAE5"),
              bytes.fromhex("F1CB1F1FB5ADF208806B89DC579DC1F8"))
SSC = 0x887022120C06C226


def test_select_ef_com():
    cmd = protect_command(KEYS, SSC + 1, 0x00, 0xA4, 0x02, 0x0C, data=bytes.fromhex("011E"))
    assert cmd.hex().upper() == "0CA4020C158709016375432908C044F68E08BF8B92D635FF24F800"
    resp = unprotect_response(KEYS, SSC + 2, bytes.fromhex("990290008E08FA855A5D4C50A8ED9000"))
    assert resp.sw == 0x9000


def test_read_binary():
    cmd = protect_command(KEYS, SSC + 3, 0x00, 0xB0, 0x00, 0x00, le=4)
    assert cmd.hex().upper() == "0CB000000D9701048E08ED6705417E96BA5500"
    resp = unprotect_response(KEYS, SSC + 4, bytes.fromhex("8709019FF0EC34F9922651990290008E08AD55CC17140B2DED9000"))
    assert resp.data.hex().upper() == "60145F01"


def test_tampered_response_rejected():
    import pytest

    with pytest.raises(ValueError):
        unprotect_response(KEYS, SSC + 4, bytes.fromhex("8709019FF0EC34F9922651990290008E08AD55CC17140B2DEE9000"))
