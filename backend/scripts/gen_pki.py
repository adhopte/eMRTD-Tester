"""Generate the issuer test PKI (IACA + Document Signer) as files for Render Secret Files or a volume.

    python scripts/gen_pki.py --base-url https://emrtd-pid-issuer.onrender.com --out pki-out

Writes iaca.key, iaca.pem, ds.key, ds.pem. Keep the *.key files secret.
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from app.pki import load_or_create  # noqa: E402

ap = argparse.ArgumentParser()
ap.add_argument("--base-url", required=True, help="public HTTPS URL of the deployed issuer")
ap.add_argument("--out", default="pki-out")
ap.add_argument("--country", default="EU")
ap.add_argument("--organization", default="Dummy Citizen PID Issuer")
args = ap.parse_args()
pki = load_or_create(args.out, args.country, args.organization, args.base_url)
print(f"wrote {args.out}/iaca.key iaca.pem ds.key ds.pem")
print(f"IACA subject: {pki.iaca_cert.subject.rfc4514_string()}")
print(f"DS valid until: {pki.ds_cert.not_valid_after_utc:%Y-%m-%d}")
