# eMRTD → EU PID Wallet

An Android wallet app plus a backend that turns a passport or ID card into an **EU Person Identification Data (PID)** credential in **ISO/IEC 18013-5 mdoc** format (`eu.europa.ec.eudi.pid.1`). The PID is signed by a dummy "citizen PKI" issuer. You can present it to EU wallet **web verifiers** (OpenID4VP) and to **proximity verifiers** (ISO 18013-5 over QR/NFC engagement and BLE).

You can onboard in one of two ways:

| Path | For | What gets verified | Assurance |
|---|---|---|---|
| **MRZ scan → NFC chip read** | ICAO eMRTDs (e-passports, eID cards) | Passive Authentication, Active Authentication, Chip Authentication, all checked by the server | high |
| **Document image scan** | documents without a chip, or with an unreadable chip | image quality, format/geometry, photocopy and screen-recapture heuristics, portrait presence, MRZ check digits, VIZ↔MRZ consistency, expiry, specimen marks | low (heuristic) |

> ⚠️ **Test system.** The issuer PKI is generated locally and is not trusted by anyone. The image-based authenticity checks are heuristics, not forensic document verification. Don't use either for real identity assurance without the hardening described below.

```
┌──────────────── Android app (Kotlin / Compose) ───────────────┐          ┌────────────── Backend (Python / FastAPI) ──────────────┐
│ ML Kit MRZ OCR ─► JMRTD: PACE/BAC, read SOD + DG1/2/11/12/14/15 │  HTTPS   │ /emrtd/challenge  AA nonce + CA ephemeral key and       │
│   ├─ INTERNAL AUTHENTICATE(server nonce)            (AA)  ─────┼─────────►│                   pre-computed SM command               │
│   └─ MSE:Set KAT(server key) + relay SM command     (CA)       │          │ /emrtd/issue      PA (SOD sig, DG hashes, DSC→CSCA),    │
│ CameraX document capture + ML Kit OCR ─────────────────────────┼─────────►│                   AA verify, CA verify → PID mdoc       │
│                                                                │          │ /document/issue   image + MRZ/VIZ checks → PID mdoc      │
│ EUDI wallet-core: Android-Keystore device key, mdoc storage,   │◄─────────┤ IssuerSigned: IssuerSignedItems + MSO (device key bound)│
│   ISO 18013-5 proximity (QR/NFC + BLE), OpenID4VP              │          │   COSE_Sign1 by Document Signer ◄─ IACA (dummy PKI)     │
└────────────────────────────────────────────────────────────────┘          └─────────────────────────────────────────────────────────┘
```

## How genuineness is checked

**Passive Authentication** (`backend/app/emrtd/passive_auth.py`): the server parses EF.SOD (CMS SignedData with an LDS Security Object). It checks `messageDigest` against the eContent, verifies the signature with the Document Signer certificate (RSA PKCS#1 v1.5, RSA-PSS or ECDSA), and checks each data group hash. It then verifies the DSC against the CSCA trust anchors in `backend/data/csca/` (certificates or ICAO/national master lists).

**Active Authentication** (`active_auth.py`): the **server** generates the 8-byte challenge. The phone sends it to the chip with INTERNAL AUTHENTICATE, and the server verifies the response with the DG15 key. It supports RSA ISO/IEC 9796-2 scheme 1 (SHA-1/224/256/384/512 trailers) and ECDSA, using the algorithm from DG14 ActiveAuthenticationInfo.

**Chip Authentication, verified by the server** (`chip_auth.py`, `secure_messaging.py`). Ordinary CA only convinces the terminal that runs it. A tampered app could simply claim "CA OK". Here the backend acts as the CA terminal:

1. It generates the ephemeral key pair on the chip's DG14 domain parameters (ECDH or DH) and derives `KSenc`/`KSmac` itself.
2. It pre-computes a secure-messaging protected `READ BINARY` of DG1 (SSC = 1).
3. The phone sends the server's ephemeral public key to the chip. For 3DES this is MSE:Set KAT; for AES it is MSE:Set AT plus General Authenticate. The phone then relays the protected command byte-for-byte.
4. The server checks the response MAC (SSC = 2) and that the decrypted bytes equal the submitted DG1.

Only a chip that holds the DG14 private key can derive those session keys. That makes CA a clone-detection proof the backend can check without trusting the phone. 3DES and AES-128/192/256 are supported. The secure-messaging code matches the ICAO 9303-11 Appendix D worked example byte-for-byte (see `tests/test_secure_messaging.py`).

**Issuance policy** (set with environment variables prefixed `PID_`, see `backend/app/config.py`):
- PA must pass: SOD signature and every DG hash.
- If the chip supports AA or CA, at least one of them must pass (`PID_REQUIRE_CHIP_GENUINENESS`, default `true`). A chip that copies the data groups but can't answer AA/CA is rejected as a clone.
- Set `PID_REQUIRE_CSCA_TRUST=true` to require that the DSC chains to an imported CSCA. It defaults to `false` so you can test with passports whose CSCA you haven't imported; the report then shows a warning.
- The document must not be expired. Each session is single-use and expires after `PID_SESSION_TTL_SECONDS`.

**Image path** (`backend/app/docscan/`). The app sends either a live camera capture or an image **uploaded** from the gallery or files (JPEG, PNG, WebP or HEIC, converted to upright JPEG on the phone). Uploads are accepted but flagged: the report shows a `capture_source` warning, and the PID evidence records `image_source: upload`, because a stored file may have been edited. The server:
- checks resolution, sharpness (Laplacian variance), glare and exposure
- finds and rectifies the document, then compares its aspect ratio to ICAO TD1/TD2/TD3
- flags greyscale photocopies with a colourfulness metric and screen re-captures with FFT moiré peaks
- detects the face and crops it as the PID portrait
- reads the MRZ from on-device ML Kit text plus server Tesseract, validates all check digits, and fuzzy-matches the VIZ fields against the MRZ
- checks expiry and SPECIMEN marks

This produces a weighted score. A PID is issued only if nothing fails and the score is at least `PID_SCAN_MIN_SCORE`. `docscan/providers.py` is the hook for a commercial document-authenticity service (UV/IR, holograms, template databases), which you need for real assurance.

## The PID mdoc

- Doctype and namespace: `eu.europa.ec.eudi.pid.1`, with elements from the PID Rulebook:
  - `family_name`, `given_name`, `birth_date` (full-date), `place_of_birth`, `nationality` (alpha-2 array)
  - `sex` (ISO 5218), `portrait` (JPEG; DG2 JPEG2000 is transcoded)
  - `resident_address`, `personal_administrative_number`
  - `age_over_18/21`, `age_in_years`, `age_birth_year`
  - `issuing_authority`, `issuing_country`, `document_number`
  - `date_of_issuance`/`date_of_expiry`, plus the legacy `issuance_date`/`expiry_date` and `birth_place` so older verifiers work too
- A second namespace, `org.emrtd-tester.evidence.1`, records how the identity was proven: chip or image, and which checks passed.
- Signing structure:
  - The MSO uses SHA-256 digests over randomly salted IssuerSignedItems, with randomised digest IDs.
  - The **wallet's hardware-backed device key** is in `deviceKeyInfo`, so presentations carry DeviceAuth.
  - The COSE_Sign1 is ES256 with the DS certificate in `x5chain`.
- The PID never outlives the source document, and lasts at most `PID_PID_MAX_VALIDITY_DAYS` (365).
- PKI (`backend/app/pki.py`) follows the ISO 18013-5 Annex B profiles:
  - IACA root: keyCertSign/cRLSign, pathLen 0, issuerAltName, CRL distribution point
  - Document Signer: EKU `1.0.18013.5.1.2`, validity under 457 days, AKI/SKI
  - Served at `/pki/iaca.pem`, `/pki/iaca.der`, `/pki/ds.pem` and `/pki/crl.der`

Interoperability is tested in two places:
- `android/app/src/test/.../MdocInteropTest.kt` parses a backend-issued PID with **multipaz**, the mdoc stack the EUDI wallet-core uses. It verifies the COSE signature, the DS→IACA chain, the MSO, the device-key binding and every element digest.
- The backend tests also verify every issued credential with an independent verifier (`app/mdoc/verifier.py`).

## Running the backend

```bash
cd backend
pip install -r requirements-dev.txt        # plus: apt install tesseract-ocr (optional server-side OCR)
python -m pytest -q                        # 23 tests: MRZ, SM vectors, PA/AA/CA end-to-end, image pipeline
PID_PUBLIC_BASE_URL=http://<your-lan-ip>:8000 uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Or with Docker: `docker build -t pid-issuer backend && docker run -p 8000:8000 -v $PWD/pki:/srv/data/pki pid-issuer`.

The test IACA and DS keys are generated on first start in `PID_PKI_DIR` (default `data/pki`, git-ignored). To verify real passports' Document Signers, put CSCA certificates or master lists in `backend/data/csca/`. The German BSI master list and the ICAO PKD master list both work.

| Endpoint | Purpose |
|---|---|
| `POST /api/v1/emrtd/challenge` | `{dg14?}` → session, AA challenge, CA ephemeral key + protected command |
| `POST /api/v1/emrtd/issue` | SOD, DGs, AA signature, CA response, wallet COSE device key → report + `issuer_signed` |
| `POST /api/v1/document/issue` | multipart `front`, `back?`, `device_ocr_text`, `document_kind`, `device_key` → report + `issuer_signed` |
| `POST /api/v1/mdoc/verify` | self-check of an `issuer_signed` blob against the issuer IACA |
| `GET /pki/iaca.pem` | trust anchor to import into verifiers |

## Deploying the backend on Render

The repo includes a Render Blueprint (`render.yaml`) that builds `backend/Dockerfile`. You need Docker rather than Render's native Python runtime, because the image installs Tesseract. The container listens on Render's `$PORT`.

1. **Generate the issuer PKI once, locally.** Render's filesystem is wiped on every deploy or restart. Without a persistent PKI, each restart would create a new IACA, and verifiers would reject every PID issued before it.
   ```bash
   cd backend && pip install -r requirements.txt
   python scripts/gen_pki.py --base-url https://emrtd-pid-issuer.onrender.com --out pki-out
   ```
   Use your real service URL; the IACA and DS certificates embed it. Keep `pki-out/*.key` private and never commit them.
2. **Create the service.** In the Render dashboard choose **New → Blueprint**, connect this GitHub repo and apply `render.yaml`. When prompted, set `PID_PUBLIC_BASE_URL` to the service URL (e.g. `https://emrtd-pid-issuer.onrender.com`).
   To create it by hand instead: **New → Web Service → Docker**, set root directory `backend`, health check path `/health`, and the same environment variables as in `render.yaml`.
3. **Upload the PKI as Secret Files.** Open the service, go to **Environment → Secret Files**, and add four files named `iaca.key`, `iaca.pem`, `ds.key` and `ds.pem` with the contents from `pki-out/`. Render mounts them read-only at `/etc/secrets`, and `PID_PKI_DIR=/etc/secrets` points the app there. Redeploy.
4. **Check it.** `https://<service>.onrender.com/health` should return `{"status":"ok",...}`. `/pki/iaca.pem` must return the same certificate as `pki-out/iaca.pem`. If the logs say "could not persist the generated PKI", the secret files are missing.
5. **Point the app at it.** Either open **Settings** in the app and enter the service URL, or build the app with the URL as default: `./gradlew assembleDebug -PissuerUrl=https://<service>.onrender.com`.

Notes:
- **Free plan:** the service sleeps after about 15 minutes idle and takes up to a minute to wake. The app pings `/health` when you tap *Add PID*, but a cold start can still interrupt an NFC read. If that happens, hold the document to the phone again. The `starter` plan avoids sleeping.
- **Instances:** run a single instance. Issuance sessions (AA/CA challenges) are kept in memory, so scaling out needs a shared session store.
- **CSCA certificates:** they're public, so commit them to `backend/data/csca/` and they're baked into the image. Then set `PID_REQUIRE_CSCA_TRUST=true`.
- **DS rotation:** the Document Signer certificate is valid for about 15 months. Before it expires, regenerate with `gen_pki.py` and replace the secret files. Keep the old `iaca.*` files in the output directory: the script reuses an existing IACA and only renews the DS.

## Building the Android app

Requirements: JDK 17+ and Android SDK 36. The app runs on Android 10 (API 29) and later.

**Phones without NFC** can install and use the app: NFC, camera, Bluetooth and location are all declared optional. What changes on those phones:
- **Adding a PID:** image scan is offered as the main option. The chip option is shown disabled, with a note that it needs an NFC phone.
- **NFC switched off:** the app offers a shortcut to NFC settings.
- **Chip read problems:** the chip-read screen always offers "scan images instead", for phones without NFC and for chips that can't be read.
- **Presenting:** QR/BLE proximity and OpenID4VP work without NFC. Only tap-to-engage needs it.

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
```

In the app, open **Settings** and set the issuer URL. The default is `http://10.0.2.2:8000`, which is your computer when running in the emulator; on a phone, use your computer's LAN IP. Debug builds allow cleartext HTTP; release builds require HTTPS.

Main libraries:
- **JMRTD 0.8.8** handles PACE (MRZ or CAN, falling back to BAC), reading the data groups, AA and the CA key exchange.
- **ML Kit** does on-device OCR and **CameraX** handles the camera.
- **EUDI `wallet-core` 0.30.2** provides Android-Keystore device keys, credential storage, **ISO 18013-5 proximity presentation** (QR and NFC engagement, BLE transfer) and **OpenID4VP** remote presentation, plus the consent UI flow.

### Installing the APK on a phone

Download `emrtd-pid-wallet-debug.apk` from the repo's **Releases → Development build (latest push)**. This is a plain APK, so you don't need to unzip anything. It runs on Android 8.0+ and on 64-bit or 32-bit ARM phones.

If the phone says **"App not installed"**:
- **An older copy is installed.** Uninstall it first. Builds made before version 1.0.1 were signed with random debug keys, so they can't be updated in place. Builds from 1.0.1 onwards all share the public dev key in `android/keystore/`, so later versions install as updates.
- **Not enough storage.** Free at least about 400 MB; the APK is about 130 MB.
- **Play Protect or an "unknown sources" prompt.** Choose *More details → Install anyway*, and allow installs from your browser or file manager.
- **Still failing?** Connect the phone with USB debugging on and run `adb install -r emrtd-pid-wallet-debug.apk`. It prints the exact reason, for example `INSTALL_FAILED_NO_MATCHING_ABIS` or `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Presenting the PID

- **Proximity verifiers** (ISO 18013-5), such as the EUDI reference verifier app or the multipaz verifier:
  1. Tap **Show QR**, or tap the phone on an NFC-engagement reader.
  2. The verifier connects over BLE and requests elements.
  3. You approve on the consent screen.
- **Web verifiers** (OpenID4VP), such as `verifier.eudiw.dev`:
  1. Choose the PID in *mso_mdoc* format.
  2. Scan the verifier's QR with the phone camera, or open the same-device link. The app handles the `openid4vp://`, `eudi-openid4vp://`, `mdoc-openid4vp://` and `haip-vp://` schemes.
  3. You approve on the consent screen.
- **Issuer trust**: signature, digest and device-auth checks will pass, but a verifier only reports the issuer as *trusted* if it trusts the dummy IACA. Import `GET /pki/iaca.pem` into the verifier's issuer trust store; most test verifiers and self-hosted EUDI verifier deployments let you add issuer certificates. The public EUDI test services only trust their own test issuers.
- **Verifier trust in the wallet**: the wallet runs with `ReaderAuthPolicy.DoNotEnforce`. It shows whether a verifier's certificate chains to a trusted reader CA but leaves the decision to the user. Add reader and verifier CA certificates with `configureReaderTrustStore` in `WalletApp.kt` to mark them as trusted.

## Hardening before production

- Replace the dummy PKI with HSM-held IACA and DS keys, rotate DS certificates, and publish a real CRL or status list.
- Import official CSCA master lists, set `PID_REQUIRE_CSCA_TRUST=true`, and check CSCA/DSC CRLs.
- Add a proper document-authenticity provider, face matching and liveness (selfie vs. DG2 or the document portrait) to bind the person to the document.
- Require wallet attestation (WIA/WUA) and key attestation for the device key, and move issuance to OpenID4VCI.
- Enable `userAuthenticationRequired` for device keys (biometric unlock at presentation) in `WalletApp.kt`.
- Replace the in-memory session store with a shared one (such as Redis) if you run several workers, and put the backend behind TLS.
