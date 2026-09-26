Drop CSCA trust anchors here: individual certificates (`.pem`, `.crt`, `.cer`, `.der`) or
CSCA Master Lists (`.ml`, e.g. the German BSI master list or the ICAO PKD master list).
They are loaded at start-up; set `PID_REQUIRE_CSCA_TRUST=true` to refuse passports whose
Document Signer does not chain to one of them.
