"""MRZ OCR repair: realistic OCR corruptions of ICAO specimen MRZs (no real personal data)."""
import itertools

from app.emrtd import mrz
from app.emrtd.mrz_repair import repair

TD3 = ("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\n"
       "L898902C36UTO7408122F1204159ZE184226B<<<<<10")
TD1 = ("I<UTOD231458907<<<<<<<<<<<<<<<\n"
       "7408122F1204159UTO<<<<<<<<<<<6\n"
       "ERIKSSON<<ANNA<MARIA<<<<<<<<<<")
VIZ = "UTOPIA PASSPORT\nERIKSSON\nANNA MARIA\n12 AUG 1974\nL898902C3"


def test_clean_mrz_passes_through():
    assert repair(TD3).to_dict() == mrz.parse(TD3).to_dict()


def test_td3_common_ocr_confusions():
    # filler read as K/E/5, name separator '<' read as 'S', 'O'/'I' in the birth date, sex F read as E
    l1 = "P<UTOERIKSSON<<ANNASMARIA<<K<<K5EKKKEKEKEKES"
    l2 = "L898902C36UTO74O8I22E12O4159ZE184226B<<<<<10"
    m = repair(l1 + "\n" + l2, VIZ)
    assert m is not None and m.valid
    assert (m.document_number, m.birth_date_raw, m.sex, m.expiry_date_raw) == ("L898902C3", "740812", "F", "120415")
    assert (m.primary_identifier, m.secondary_identifier) == ("ERIKSSON", "ANNA MARIA")


def test_td1_extra_characters_and_filler_noise():
    # a spurious digit inside the expiry date plus 'K' read for '<' in the filler run
    l2 = "7408122F12504159UTO<<K<K<<<<<<<6"
    m = repair(TD1.split("\n")[0] + "\n" + l2 + "\n" + TD1.split("\n")[2])
    assert m is not None and m.valid
    assert (m.document_number, m.expiry_date_raw) == ("D23145890", "120415")


def test_garbage_is_refused():
    assert repair("P<UTOERIKSSON<<ANNA<<<<<<<<<<<<<<<<<<<<<<<<<\nXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX") is None


def _ambiguous_case():
    """A document number where 'Z' at position 0 is misread as '1' while position 6 holds a real
    '1'. Positions 0 and 6 share check-digit weight 7, so 'Z.....1..' and '1.....Z..' satisfy the
    document and composite check digits equally: the MRZ alone cannot tell them apart."""
    doc = "Z23451189"  # position 6 is '1'
    corrupted_doc = "1" + doc[1:]
    dob, exp = "740812", "320415"
    line2 = f"{doc}{mrz.check_digit(doc)}UTO{dob}{mrz.check_digit(dob)}F{exp}{mrz.check_digit(exp)}" + "<" * 15
    line2 += mrz.check_digit(line2[0:10] + line2[13:20] + line2[21:43])
    text = "P<UTOERIKSSON<<ANNA<<<<<<<<<<<<<<<<<<<<<<<<<\n" + corrupted_doc + line2[9:]
    return doc, text


def test_ambiguous_repair_never_guesses_a_non_prefix_letter():
    doc, text = _ambiguous_case()
    m = repair(text)
    # either refused, or resolved by the document-number shape prior (letters form a prefix)
    assert m is None or m.document_number == doc


def test_ambiguous_repair_uses_printed_document_number():
    doc, text = _ambiguous_case()
    m = repair(text, "ERIKSSON\nANNA\n" + doc)
    assert m is not None and m.document_number == doc


def test_names_split_when_printed_names_run_together():
    # VIZ OCR glued the given names ("ANNAMARIA") and misread one ("AWNA"); MRZ letters are kept
    l1 = "P<UTOERIKSSON<<ANNASMARIAS<<K5<<K5EKKKEKEKEK"
    l2 = TD3.split("\n")[1]
    m = repair(l1 + "\n" + l2, "ERIKSSON\nAWNA\nANNAMARIA")
    assert m is not None and m.secondary_identifier == "ANNA MARIA"
