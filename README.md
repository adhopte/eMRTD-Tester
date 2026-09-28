# getYourID Wallet by IN Groupe: eMRTD → EU PID

An Android wallet app plus a backend that turns a passport or ID card into an **EU Person Identification Data (PID)** credential in **ISO/IEC 18013-5 mdoc** format (`eu.europa.ec.eudi.pid.1`). The PID is signed by a dummy "citizen PKI" issuer. You can present it to EU wallet **web verifiers** (OpenID4VP) and to **proximity verifiers** (ISO 18013-5 over QR/NFC engagement and BLE).

The wallet also receives **attestations** (ISO 23220 Photo ID with portrait, EU age verification) and PIDs from **QR-code credential offers** (OpenID4VCI pre-authorized code flow). Every onboarding ends with a **live selfie**: the issuer matches it against the chip or document portrait and checks a head-turn liveness challenge. The wallet is protected by a **wallet PIN or the phone's biometrics**, chosen when the wallet is set up, and keeps a local **activity history**.

You can onboard in one of two ways:

| Path | For | What gets verified | Assurance |
|---|---|---|---|
| **MRZ scan → NFC chip read** | ICAO eMRTDs (e-passports, eID cards) | Passive Authentication, Active Authentication, Chip Authentication, all checked by the server | high |
| **Document image scan** | documents without a chip, or with an unreadable chip | image quality, format/geometry, photocopy and screen-recapture heuristics, portrait presence, MRZ check digits, VIZ↔MRZ consistency, expiry, specimen marks | low (heuristic) |

> ⚠️ **Test system.** The issuer PKI ("IN Groupe Issuer IACA (TEST)") is a test PKI whose private keys are published in this repo, so it must not be trusted outside testing. The image-based authenticity checks are heuristics, not forensic document verification. Don't use either for real identity assurance without the hardening described below.

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

**Auto capture.** On by default in the scan screen. The app runs on-device OCR on the camera preview about 4 times a second and takes the photo itself when:
- **Passport photo page or ID card back:** the MRZ is read with valid check digits.
- **ID card front:** at least 5 lines of text span 45% or more of the frame and no MRZ is visible. If the MRZ side is shown, it prompts "show the FRONT first".

In both cases the text must hold still over 3 consecutive analysed frames. A guide frame and status line show progress ("Move closer", "Hold still…", "Captured"). Manual capture and upload remain available.

**Image path** (`backend/app/docscan/`). The app sends either a live camera capture or an image **uploaded** from the gallery or files (JPEG, PNG, WebP or HEIC, converted to upright JPEG on the phone), or a **PDF scan**. PDFs are rendered at 300 DPI, on the phone by Android's `PdfRenderer` or on the backend by `pypdfium2` for API clients that post a PDF directly. Page 1 is the front and page 2 the back; a single page with both sides of an ID card also works. Password-protected PDFs are rejected. Uploads are accepted but flagged: the report shows a `capture_source` warning, and the PID evidence records `image_source: upload`, because a stored file may have been edited. The server:
- checks resolution, sharpness (Laplacian variance), glare and exposure
- finds and rectifies the document, then compares its aspect ratio to ICAO TD1/TD2/TD3
- flags greyscale photocopies with a colourfulness metric and screen re-captures with FFT moiré peaks
- detects the face and crops it as the PID portrait
- reads the MRZ from on-device ML Kit text plus server Tesseract, validates all check digits, and fuzzy-matches the VIZ fields against the MRZ
- checks expiry and SPECIMEN marks

This produces a weighted score. A PID is issued only if nothing fails and the score is at least `PID_SCAN_MIN_SCORE`. `docscan/providers.py` is the hook for a commercial document-authenticity service (UV/IR, holograms, template databases), which you need for real assurance.

## Selfie: face match and liveness

After the chip read or the document capture, the app asks for a live selfie (`ui/SelfieScreen.kt`, `liveness/LivenessAnalyzer.kt`):

1. ML Kit face detection on the front camera guides the user through a random order of challenges: blink, turn the head to one side, then the other, and finally look straight at the camera.
2. One frame is kept per pose: the frontal selfie plus the two head-turn frames. They go to the issuer with the issuance request, along with a report of the challenges.
3. The backend (`backend/app/face.py`) uses OpenCV **YuNet** (detection) and **SFace** (recognition):
   - **Face match:** the selfie must match the DG2 portrait (chip) or the portrait cropped from the document image. The SFace cosine similarity must be at least 0.363, OpenCV's recommended threshold.
   - **Liveness re-check:** both head-turn frames must show the same person as the selfie, turned clearly in *opposite* directions (estimated from the facial landmarks), while the selfie is frontal.
4. A failed match or liveness check rejects the issuance. The result is recorded in the PID's evidence namespace (`face_match`, `face_match_score`, `liveness`).

A printed photo or a static screen can't turn its head, but this is **not a certified presentation-attack detection** (ISO/IEC 30107-3): a replayed video of the holder could pass. The models (Apache-2.0) are downloaded with checksum verification when the Docker image is built (`backend/scripts/fetch_models.sh`). `PID_REQUIRE_SELFIE=true` makes the selfie mandatory; it defaults to `false` so older app versions keep working, and the app offers a "Skip" link that is recorded as `face_match: not_performed`.

## Credential offers (OpenID4VCI), attestations and the issuer portal

The backend is also an **OpenID4VCI 1.0** issuer (`backend/app/oid4vci.py`) for the **pre-authorized code flow**:

| Endpoint | Purpose |
|---|---|
| `/.well-known/openid-credential-issuer`, `/.well-known/oauth-authorization-server` | issuer and authorization-server metadata |
| `/oid4vci/offers/{id}` | the credential offer behind `openid-credential-offer://?credential_offer_uri=…` |
| `/oid4vci/token` | pre-authorized code + 6-digit **transaction code** (5 wrong codes revoke the offer) |
| `/oid4vci/nonce`, `/oid4vci/credential` | `c_nonce`, then `mso_mdoc` credentials bound to the proof key |
| `/wallet-provider/key-attestation` | **TEST Wallet Provider**: signs `key-attestation+jwt` for the wallet's device keys |
| `/wallet-provider/wallet-attestation` | **TEST Wallet Provider**: signs the Wallet Instance Attestation (`oauth-client-attestation+jwt`) for attestation-based client authentication; the token endpoint verifies it and its PoP when sent |

Credential types (`backend/app/attestations.py`), all ISO 18013-5 mdocs signed by the same Document Signer:
- **PID** (`eu.europa.ec.eudi.pid.1`), with portrait.
- **Photo ID** (`org.iso.23220.photoID.1`), with portrait. When issued from a chip it also carries the ICAO **DTC** namespace (`org.iso.23220.dtc.1`) with the chip's EF.SOD, DG1 and DG2, so a verifier can re-run Passive Authentication itself.
- **Age verification** (`eu.europa.ec.av.1`), with age-over flags only: no name, no photo.

How offers are created:
- **In the app:** after a PID is issued, the result screen offers the Photo ID and age attestations derived from the same verified evidence. There is no transaction code, because the app is already on the authenticated channel.
- **Web issuer portal:** `https://<backend>/issuer`.
  1. Upload a passport or ID-card photo or PDF. It goes through the same document checks as the app. For testing, you can instead type self-asserted data; those credentials are marked `manual_entry_unverified`.
  2. Pick the credentials to offer.
  3. You get a QR code, the transaction code and a live status ("wallet connected", "delivered").
- **API:** `POST /api/v1/oid4vci/offers`.

About the proofs: EUDI wallet-core only sends JWT proofs that carry a Wallet Provider **key attestation** (OpenID4VCI 1.0 Appendix D, the ARF Wallet Unit Attestation). The backend therefore includes a TEST Wallet Provider (`backend/app/wallet_provider.py`, key in `data/pki/wallet_provider.*`). The issuer accepts proofs signed by an attested key and issues one credential per attested key. Plain `jwk` proofs from other wallets are accepted too. DPoP and credential-response encryption are not offered; the app encrypts responses whenever an issuer supports it.

In the app, **Scan** (bottom bar) reads any wallet QR code:
- A credential offer opens *Add to wallet*: issuer, offered credentials, and the transaction-code field if one is needed.
- An OpenID4VP request opens the consent screen.
- *Paste a link* covers links received by e-mail or chat.
- Offers from other OpenID4VCI issuers work too. The wallet picks client authentication per issuer from its authorization server metadata:
  - `attest_jwt_client_auth` advertised → **attestation-based client authentication**: a Wallet Instance Attestation (`oauth-client-attestation+jwt`) from the TEST Wallet Provider, plus a PoP signed by a wallet key in the Android Keystore.
  - otherwise → public client (`client_id=getyourid-wallet`).

  An issuer that checks *who* signed the attestation must trust the TEST Wallet Provider certificate (`GET /wallet-provider/certificate.pem`); otherwise it answers `invalid_client`.

`android/app/src/test/.../Oid4vciEndToEndTest.kt` runs wallet-core's own `OpenId4VciManager` against a live backend: offer, transaction code, key attestation, three credentials stored.

```bash
./gradlew :app:testDebugUnitTest --tests '*Oid4vciEndToEnd*' -Pe2eIssuer=https://emrtd-pid-issuer.onrender.com
```

## Wallet security and activity

- **Wallet unit initialisation.** After the tutorial, the user picks one of two unlock methods (`security/WalletLock.kt`):
  - **Biometrics:** BiometricPrompt with the phone's fingerprint or face unlock, falling back to the screen lock.
  - **Wallet PIN:** 6 digits, stored only as a salted PBKDF2-SHA256 hash. Trivial PINs are refused, and repeated failures back off exponentially.
- **Locking.** The wallet locks on start and after one minute in the background.
- **Confirmation.** Sharing data, deleting a credential and changing the unlock method all ask for the PIN or biometrics. Confirm-before-share can be switched off in Settings.
- **Activity history** (Settings → *Wallet activity history*) lists, per day:
  - wallet creation and security changes
  - credentials added, rejected or failed, with the issuer
  - every presentation: verifier, channel and the exact attributes shared
  - declined requests and deletions

  The history stays on the phone (`activity.json` in no-backup storage) and can be filtered or cleared.

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

The repo includes a Render Blueprint, `render.yaml`. It builds `backend/Dockerfile`, a Docker image with Tesseract that listens on Render's `$PORT`.

The **test issuer PKI is committed** in `backend/data/pki/` and baked into the image, so a deploy needs no secrets. Its certificates embed `https://emrtd-pid-issuer.onrender.com`. Because the repo is public, those private keys are public too: treat the IACA as a throwaway test trust anchor.

1. Open **https://render.com/deploy?repo=https://github.com/adhopte/eMRTD-Tester** and sign in with GitHub. Grant Render access to the repository if asked.
2. Render reads `render.yaml` and shows the service `emrtd-pid-issuer` on the free plan in Frankfurt. Click **Apply** or **Deploy Blueprint**.
3. Wait for the first build (about 5–8 minutes), then check:
   - `https://emrtd-pid-issuer.onrender.com/health` returns `{"status":"ok",...}`
   - `https://emrtd-pid-issuer.onrender.com/pki/iaca.pem` matches `backend/data/pki/iaca.pem` (SHA-256 fingerprint `EB:84:17:E2:…:C4:16`)
4. The app's default issuer URL is already `https://emrtd-pid-issuer.onrender.com`.

Render may give the service a different URL, e.g. `emrtd-pid-issuer-abcd.onrender.com` if the name is taken. In that case:
- In the app, change **Settings → issuer URL**.
- Optionally regenerate the PKI so the certificate URLs match: `python backend/scripts/gen_pki.py --base-url https://<your-url> --out backend/data/pki` after deleting the old files, then update `PID_PUBLIC_BASE_URL` in `render.yaml`, commit and push. Pushing redeploys automatically; only changes under `backend/` or to `render.yaml` trigger a redeploy.

Notes:
- **Free plan:** the service sleeps after about 15 minutes idle and takes up to a minute to wake. The app pings `/health` when you tap *Add PID*, but a cold start can still interrupt an NFC read; if that happens, hold the document to the phone again. Set `plan: starter` for an always-on instance.
- **Instances:** run a single instance. Issuance sessions (AA/CA challenges) are held in memory.
- **Real keys:** generate new ones, upload `iaca.key`, `iaca.pem`, `ds.key` and `ds.pem` as Render **Secret Files**, and set `PID_PKI_DIR=/etc/secrets`.
- **CSCA certificates:** they're public, so commit them to `backend/data/csca/`. Then set `PID_REQUIRE_CSCA_TRUST=true`.

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

### Brand flavors and localization

The app builds as two **product flavors** from the same source tree (`app/build.gradle.kts`):

| Flavor | Application ID | App name | Colors |
|---|---|---|---|
| `ingroupe` (default) | `io.github.adhopte.emrtdwallet` | getYourID Wallet | IN Groupe blue/sky-blue |
| `anipBenin` | `io.github.adhopte.emrtdwallet.anip` | Bénin IN Groupe POC | Bénin flag green/gold/red |

Each flavor supplies its own `Brand.kt` palette object, launcher icon, emblem drawable and brand strings (`app_name`, `brand_tagline`, `issuer_display_name`, …) under `app/src/<flavor>/`; everything else is shared. Build a specific flavor with:

```bash
./gradlew :app:assembleIngroupeDebug
./gradlew :app:assembleAnipBeninDebug
# APKs: app/build/outputs/apk/<flavor>/debug/app-<flavor>-debug.apk
```

Both flavors point at the same backend by default. To give the ANIP Bénin flavor its own issuer, build with `-PanipIssuerUrl=https://<its-backend>` and set `PID_ISSUER_ORGANIZATION`/`PID_ISSUING_AUTHORITY` on that backend instance (see `backend/app/config.py`) so issued credentials carry the ANIP Bénin issuer name.

The UI is localized into **English and French** (`values/strings.xml`, `values-fr/strings.xml`, plus per-flavor string overlays). The device's language is used by default; **Settings → Language** lets the user switch it per-app (`AppCompatDelegate.setApplicationLocales`, persisted automatically, works down to API 26).

Main libraries:
- **JMRTD 0.8.8** handles PACE (MRZ or CAN, falling back to BAC), reading the data groups, AA and the CA key exchange.
- **ML Kit** does on-device OCR and **CameraX** handles the camera.
- **EUDI `wallet-core` 0.30.2** provides Android-Keystore device keys, credential storage, **ISO 18013-5 proximity presentation** (QR and NFC engagement, BLE transfer) and **OpenID4VP** remote presentation, plus the consent UI flow.

### Installing the APK on a phone

Download **`getyourid-wallet.apk`** (release build, about 45 MB) from the repo's **Releases → Development build (latest push)**. Use `getyourid-wallet-debug.apk` only if you need a plain-HTTP issuer on your LAN. This is a plain APK, so you don't need to unzip anything. It runs on Android 8.0+ and on 64-bit or 32-bit ARM phones.

If the phone says **"App not installed"**:
- **An older copy is installed.** Uninstall it first. Builds made before version 1.0.1 were signed with random debug keys, so they can't be updated in place. Builds from 1.0.1 onwards all share the public dev key in `android/keystore/`, so later versions install as updates.
- **Not enough storage.** Free at least about 400 MB; the APK is about 130 MB.
- **Play Protect or an "unknown sources" prompt.** Choose *More details → Install anyway*, and allow installs from your browser or file manager.
- **Generic "The app wasn't installed" from Files by Google:** open the APK from Chrome's *Downloads* or the system *Files* app instead. The system installer names the actual reason, such as "isn't compatible", "conflicts with an existing package" or "package appears to be invalid". Also check *Settings → Apps* (including Dual apps, Second space and Work profile) for a leftover copy.
- **Still failing?** Connect the phone with USB debugging on and run `adb install -r getyourid-wallet-debug.apk`. It prints the exact reason, for example `INSTALL_FAILED_NO_MATCHING_ABIS` or `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Presenting the PID

- **Proximity verifiers** (ISO 18013-5), such as the EUDI reference verifier app or the multipaz verifier:
  1. Tap **Show QR** in the bottom bar (or on a credential), or tap the phone on an NFC-engagement reader.
  2. The verifier connects over BLE and requests elements.
  3. You approve on the consent screen.
- **Web verifiers** (OpenID4VP), such as `verifier.eudiw.dev`:
  1. Choose the PID in *mso_mdoc* format.
  2. Tap **Scan** in the wallet and point at the verifier's QR code, or open the same-device link. The app handles the `openid4vp://`, `eudi-openid4vp://`, `mdoc-openid4vp://` and `haip-vp://` schemes.
  3. The consent screen shows the verifier, whether it is registered, and the attributes grouped by credential, marking any that will be stored.
  4. Share, then confirm with your PIN or biometrics.
- **Issuer trust**: signature, digest and device-auth checks will pass, but a verifier only reports the issuer as *trusted* if it trusts the dummy IACA. Import `GET /pki/iaca.pem` into the verifier's issuer trust store; most test verifiers and self-hosted EUDI verifier deployments let you add issuer certificates. The public EUDI test services only trust their own test issuers.
- **Verifier trust in the wallet**: the wallet runs with `ReaderAuthPolicy.DoNotEnforce`. It shows whether a verifier's certificate chains to a trusted reader CA but leaves the decision to the user. Add reader and verifier CA certificates with `configureReaderTrustStore` in `WalletApp.kt` to mark them as trusted.

## Hardening before production

- Replace the dummy PKI with HSM-held IACA and DS keys, rotate DS certificates, and publish a real CRL or status list.
- Import official CSCA master lists, set `PID_REQUIRE_CSCA_TRUST=true`, and check CSCA/DSC CRLs.
- Add a proper document-authenticity provider, and replace the head-turn liveness with a certified PAD (ISO/IEC 30107-3) solution.
- Replace the TEST Wallet Provider with a real one: verify Android Key Attestation against Google's roots, add app integrity (Play Integrity), issue a Wallet Instance Attestation, and use attestation-based client authentication. Offer DPoP and credential-response encryption on the issuer.
- Enable `userAuthenticationRequired` for device keys (biometric unlock at presentation) in `WalletApp.kt`.
- Replace the in-memory session store with a shared one (such as Redis) if you run several workers, and put the backend behind TLS.
