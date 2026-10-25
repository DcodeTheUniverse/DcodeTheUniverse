# Circle Suite — Circle ID + Vault

Two native Android apps (Kotlin, Jetpack Compose, minSdk 30 / targetSdk 36) that work together:

| App | Package | Purpose |
|---|---|---|
| **Circle ID** | `com.dcode.circleid` | Proves *you are you*: fingerprint + face + active liveness (+ optional pulse), fused on-device and sealed in secure hardware. |
| **Vault** | `com.dcode.vault` | Encrypted vault for ideas, notes, files and passwords, with folders, instant search by name and content, a text editor and a README/Markdown reader. Unlocks through Circle ID. |

Everything runs offline. Neither app has the `INTERNET` permission.

---

## Circle ID

### Verification pipeline

1. **Fingerprint (Class 3).** `BiometricPrompt` with a `CryptoObject`. A match is what lets Android Keystore decrypt Circle ID's profile key. That key is an AES-256-GCM key, StrongBox-backed when available, auth-per-use, and invalidated if a fingerprint is added. On Samsung ultrasonic phones this step *is* the Qualcomm 3D Sonic matcher.
2. **Face match.** CameraX front camera → ML Kit face detection with landmarks → 5-point similarity alignment → **MobileFaceNet** (Apache-2.0, 4 MB, LiteRT, on-device) → a 128-d embedding scored against a template enrolled from 7 poses.
   - The pipeline was calibrated on 1,000 LFW pairs: 98.4 % pair accuracy. Impostors peaked at a similarity of 0.34; genuine pairs averaged 0.68.
   - Strictness presets are Balanced 0.42, **Strict 0.50 (default)** and Paranoid 0.58.
3. **Active liveness.** A random sequence of challenges (blink, turn left/right, smile, look up). The *same tracked face* has to keep matching from the first frame to the last. A face swap, a second face, leaving the frame, or a static photo all fail. The yaw and pitch sign conventions are learned from your own movement during enrolment, so the turn directions are correct on any device.
4. **Pulse (optional).** Rear camera + torch through your fingertip, analysed as photoplethysmography. This proves living, perfused tissue.
5. **Device integrity.** Advisory checks: screen lock set, keys in secure hardware, no debugger, root traces, emulator.
6. **Fusion.** Every required factor must pass (AND rule). The result shows an assurance level (AL0–AL3) and an *estimated* impostor-odds figure.

### Sensor Lab: the ultrasonic / vein experiment

You asked whether the Samsung ultrasonic sensor could read finger veins. Circle ID's **Sensor Lab** tries every avenue an app has to reach that sensor on your actual phone and shows the result:

- the BiometricManager classes;
- system features;
- hidden `FingerprintManager.getSensorProperties*()` calls (via reflection);
- the SensorManager list;
- raw kernel nodes (`/dev/qbt*`, `/dev/qseecom`, and others);
- `getprop`;
- Samsung's vendor packages.

**Finding:** the raw ultrasonic frames are processed inside Qualcomm's Trusted Application in the TEE. They are never exposed to apps through any public, hidden or vendor interface. That raw data is what would hold sub-surface ridge depth and, in principle, vascular structure. So vein detection *through the fingerprint sensor* is not possible for a third-party app on a stock phone.

What Circle ID does instead:
- It uses the ultrasonic sensor the only secure way available: as the Class 3 matcher whose success releases a hardware-bound key.
- **Vein-light experiment.** With the torch transilluminating your fingertip, the Lab shows:
  - a live pulse waveform, BPM, signal quality and perfusion index;
  - an *experimental* light-absorption texture signature.
- You can capture samples, compare same-finger and different-finger scores, and save a template. You can then enable "Require vascular match (experimental)" in Settings.
- Phone cameras have IR-cut filters and the torch is white rather than 850 nm near-infrared. Treat this texture factor as research, not as a replacement for a real vein scanner. Circle ID never relies on it alone.

### Design
Monochrome. Light mode is bright white with OLED-black ink. Dark mode is the exact inverse: true `#000000` with white ink. Animations include breathing, counter-rotating identity rings (the tick pattern encodes your Circle ID), a progress ring around a circular camera, path-drawn result marks, staggered factor reveals and spring-pressed buttons.

---

## Vault

### Encryption
- **One random 256-bit data key (DEK)** encrypts everything. It is wrapped by independent key slots:
  - **Recovery passphrase:** Argon2id, 64 MiB, 3 passes → AES-256-GCM.
  - **Circle ID slot:** `Keystore(device-bound, unlocked-device-only)` ∘ `AES-GCM(HKDF(release secret))`. The release secret lives only inside Circle ID's biometric-sealed profile. Circle ID hands it over only after a successful multimodal check, ECIES-sealed (P-256) to a one-time key that Vault generated for that one request. A copied vault is useless off the phone, and Vault alone can't open itself without Circle ID.
  - **Fingerprint-only slot** (for when Circle ID isn't installed): an auth-per-use Keystore key.
- **Content format (`CSV1`):** chunked streaming AEAD with a **ChaCha20-Poly1305 inside AES-256-GCM cascade**, independent keys, and a fresh salt and keys for every write.
  - Each chunk's AAD binds the header, the file id, the chunk index and the final flag. Reordering, splicing between files, truncation and bit-flips are all detected.
  - File names, folders, password entries and the search index are encrypted too. On disk there are only random ids and ciphertext.
- **Header MAC:** the key-slot header is HMAC'd under the DEK. Offline tampering is detected, for example swapping the pinned Circle ID key.
- **Circle ID protocol checks:** Circle ID's APK signing certificate is pinned, along with its attested identity key. Each assertion is checked for its nonce, its package binding and its freshness (2 minutes), and must be VERIFIED at assurance ≥ 2.
- **Other protections:**
  - `FLAG_SECURE` blocks screenshots and recents thumbnails; overlay windows are hidden and obscured touches are filtered.
  - Copied passwords are marked sensitive and cleared after 15–60 s.
  - Auto-lock when backgrounded (immediately to 15 min) and always on screen-off.
  - Passphrase attempts back off exponentially.
  - Deleted files are overwritten before they are unlinked.
  - No cloud backup and no device transfer.

### Features
- **Items:** folders with breadcrumbs, notes (Markdown), imported files of any type, and password logins.
  - Login fields: username, password, URL, notes, and a TOTP secret (RFC 6238, `otpauth://` links supported).
  - Logins also show live 2FA codes, a strength meter and a password generator (random characters or word passphrases).
- **Instant search** while you type, across *file names and contents*:
  - an in-memory inverted index (prefix matching, case- and accent-insensitive, CJK-aware);
  - `"quoted phrases"`, ranked hits, highlighted snippets, and scope chips (Everywhere / File names / Contents);
  - about 50 ms per query on a pathological 5,000-document × 200-word test (JIT-warmed).
- **Editor:** autosave (re-encrypted with a fresh salt after every pause), undo/redo, monospace toggle, word count.
- **README / Markdown reader:** native rendering of headings, emphasis, code blocks, quotes, nested and task lists, tables and links. Remote images are deliberately never loaded.
- **Files:** image viewer with pinch-zoom, "open as text", and a decrypted export through the system file picker.
- **Backup:** export an encrypted `.zip` (ciphertext only) and restore it later with your recovery passphrase.

---

## Install

1. Install **Circle ID** (`circleid-release.apk`) and **Vault** (`vault-release.apk`). Allow "install unknown apps" for your file manager or browser.
2. Open **Circle ID** → *Set up* → capture the face poses → seal with your fingerprint.
3. Open **Vault** → *Create a vault* → choose a recovery passphrase (write it down; there is no reset) → *Circle ID*.
   - Circle ID opens, asks you to confirm the pairing, verifies you, and returns.
4. From now on, *Unlock with Circle ID* runs fingerprint → face + liveness → unlock.

If fingerprints change, Circle ID's key is destroyed by design. In that case, reset Circle ID, unlock Vault with your passphrase, and re-pair in Vault → Settings.

## Build

Requires JDK 17+ and the Android SDK (platform 36, build-tools 36).

```bash
cd circle-suite
./gradlew test            # JVM crypto/engine tests + Robolectric UI tests
./gradlew assembleRelease # → circleid/build/outputs/apk/release, vault/build/outputs/apk/release
```

**Signing:** release builds use `keystore.properties` (git-ignored) or the `CIRCLE_SUITE_KEYSTORE*` environment variables. If neither is present, they fall back to the debug key. Use the same key for every build so updates install over existing installs. CI (`.github/workflows/circle-suite.yml`) builds, tests and lints, and uploads both APKs. It signs with your key when these secrets are set: `CIRCLE_SUITE_KEYSTORE_B64`, `CIRCLE_SUITE_KEYSTORE_PASSWORD`, `CIRCLE_SUITE_KEY_ALIAS`, `CIRCLE_SUITE_KEY_PASSWORD`.

## Modules

| Module | Contents |
|---|---|
| `core/crypto` | HKDF, AEAD cascade, CSV1 streams, Argon2id, ECIES/ECDSA, TOTP, password generation |
| `core/protocol` | Circle ID ⇄ client contract, signed assertions, verifier |
| `vault-core` | Vault storage engine, key slots, header MAC, catalog, search index, Markdown parser |
| `circle-core` | Face alignment and template matching, liveness state machine, PPG pulse analyser, vascular signature, fusion |
| `circleid`, `vault` | The Android apps |

## Honest limitations

- **Face check:** this is software running on the application processor. Only the fingerprint factor is enforced by secure hardware.
  - Active liveness defeats photos and replayed videos.
  - It is not certified presentation-attack detection. A high-quality 3D mask was not tested.
- **Factor isolation:** a compromised (rooted) OS can tamper with any app-level factor. The hardware-backed fingerprint key and the Keystore slots are what remain strong in that case.
- **Impostor odds:** the figures are estimates from published false-accept rates and this calibration, not measurements of your device.
- **Testing:** the apps were built and tested on a JVM with Robolectric, plus unit tests for every crypto and engine component. The camera, biometric and Keystore paths still need on-device testing.
