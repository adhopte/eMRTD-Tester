# Issuer test PKI (committed on purpose)

`iaca.key` / `iaca.pem` (root) and `ds.key` / `ds.pem` (Document Signer) for the **IN Groupe test** PID
issuer (all names carry "(TEST)"), generated with `scripts/gen_pki.py --base-url https://emrtd-pid-issuer.onrender.com`.

**These private keys are public** (this repository is public). Anyone can sign PIDs that chain to this
IACA, so only import `iaca.pem` into test verifiers and never use it for anything real.

For a real deployment, generate new keys and supply them without committing them. Either set
`PID_PKI_DIR=/etc/secrets` and upload the four files as Render Secret Files, or mount a volume.

The DS certificate expires on 2027-12-20. Re-run `gen_pki.py` before then; it keeps the existing IACA
and renews only the DS.
