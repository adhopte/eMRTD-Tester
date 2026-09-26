from app.emrtd import mrz

ICAO_TD3 = ("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n"
            "L898902C36UTO7408122F1204159ZE184226B<<<<<10")
ICAO_TD1 = ("I<UTOD231458907<<<<<<<<<<<<<<<\n"
            "7408122F1204159UTO<<<<<<<<<<<6\n"
            "ERIKSSON<<ANNA<MARIA<<<<<<<<<<")


def test_icao_td3_specimen():
    m = mrz.parse(ICAO_TD3)
    assert m.format == "TD3"
    assert m.valid, m.checks
    assert m.primary_identifier == "ERIKSSON"
    assert m.secondary_identifier == "ANNA MARIA"
    assert m.document_number == "L898902C3"
    assert m.birth_date.isoformat() == "1974-08-12"


def test_icao_td1_specimen():
    m = mrz.parse(ICAO_TD1)
    assert m.format == "TD1"
    assert m.valid, m.checks
    assert m.document_number == "D23145890"


def test_dg1_unbroken():
    m = mrz.parse(ICAO_TD3.replace("\n", ""))
    assert m.valid


def test_find_in_noisy_text():
    text = "PASSPORT\nSurname ERIKSSON\n" + ICAO_TD3.replace("<", "«", 3) + "\nfoo"
    m = mrz.find_in_text(text)
    assert m is not None and m.valid


def test_bad_check_digit():
    bad = ICAO_TD3[:-1] + "9"
    assert not mrz.parse(bad).valid
