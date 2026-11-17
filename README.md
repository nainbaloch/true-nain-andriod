# TRUE NAIN — Video Frame Extractor & Slow-Motion Player

**Package:** `com.truenain.app` · **Current release:** v2.0.2 (versionCode 202) · **Owner:** Zulqarnain Haider (True Nain, Lahore, PK)

TRUE NAIN is an offline Android video tool for frame-accurate extraction, zooming, slow-motion playback and multi-range trimming — for devices running **Android 10+** (minSdk 29, targetSdk 35). No Internet permission, no ads, no analytics, no telemetry.

---

## 📦 Current Release

| | |
|---|---|
| **APK** | `app/build/outputs/apk/release/TRUE_NAIN_v2.0.2_FINAL.apk` |
| **SHA-256** | `D938A5B3F5A2D6D8941DBADFE21EF854FB1CE4370B07EB98459BC1EE1F76C779` |
| **Signed by** | NEW rotated key — cert SHA-256 `328c046496e1c22a93d014da6346eee2244b5889e8d5917442456b33f0a0138b` |
| **Scheme** | APK Signature Scheme v3 + **lineage** (old-key users update without uninstalling) |
| **Build** | Debug ✅ · Release (R8 minify+shrink) ✅ · Lint 0 errors |

> **v2.0.2 = v2.0.1 + full UI/UX overhaul, verified on emulator:** WCAG-AA contrast palette (dark-on-teal PLAY, dark-on-orange SAVE, deeper player blue), 48dp minimum touch targets on all 31 buttons, 13sp medium labels, ripple feedback, 2-line frame info, 20dp seekbar thumb, TalkBack content-descriptions on every icon button, dynamic Play/Pause descriptions. Emulator-verified: full frame timeline live (**F 1/301** on a 10 s/30 fps clip — keyframe-only code showed ~30), library, permissions, Customize Controls (16 toggles), landscape auto-rotate.

---

## ✨ What's new in v2.0.1 (vs v1.4.1)

1. **Full Frame Timeline** — every decoded frame is navigable (a 60 s / 30 fps video exposes ~1,800 frames, not just its ~60 keyframes). CFR, VFR and B-frame videos follow true presentation order.
2. **Advanced Frame Cache engine** — bounded LRU frame cache with ±50 logical prefetch window, dedicated prefetch decoder (never blocks seeking), stale-seek protection (AtomicLong versioning — only the latest seek renders), bitmap pooling, and a slow-motion decode-ahead buffer (16/28/40 frames at 0.25x/0.10x/0.05x).
3. **⟲ PHOTO ROTATE** — one rotation policy shared by preview, single save, batch export and screenshots. Fixes Samsung portrait/landscape metadata issues.
4. **🖼 Gallery photo rotate** — rotate ANY photo already in your Gallery (Photo Rotate dialog → 🖼). A rotated copy is saved to `Pictures/TRUE NAIN`; the original is never modified.
5. **Customize Controls — 16 toggles** — every button across Media Player, Frame Mode and Frame Extractor can be shown/hidden from one dialog (home screen → ⚙ CUSTOMIZE CONTROLS), with long-press to reorder buttons.
6. **Security: signing key rotated** — old (compromised) key retired via APK Signature Scheme v3 lineage; update compatibility preserved.

---

## 🧭 App Modes

| Mode | What it does |
|---|---|
| **Media Player** | Playback with seven speeds incl. decoded slow-motion (0.25x/0.10x/0.05x), ±5 s seeks, A–B repeat, zoom/pan, Fit/Fill/1:1, PNG screenshot |
| **Frame Mode** | Frame-by-frame navigation with seek bar, ±1/±10 steps, auto-play with AUTO/manual FPS, zoom, sharp-frame finder, per-frame PNG save, Frame Lock, Gesture Lock |
| **Frame Extractor** | EXPORT ALL FRAMES · CUSTOM FRAME RANGE / SELECTION (`1,5,10,25-100`) · ⟲ PHOTO ROTATE · library |
| **Video Library** | Scans and lists device videos; remembers your preferred mode; resume positions |

All exports land in **`Pictures/TRUE NAIN/`**.

---

## 🔨 Building

Requirements: **JDK 17**, **Android SDK (compileSdk 35)**, Gradle wrapper included.

```bat
:: quick build (debug + release)
BUILD_WINDOWS.bat

:: or directly
gradlew.bat :app:assembleDebug
gradlew.bat :app:assembleRelease
```

`keystore.properties` (project root) must point to the release keystore — on this machine it already points to `Master G\Keys\true_nain_v2_release.jks`. **Never commit or share this file.** (`keystore.properties.example` is the safe template.)

### ⚠️ Mandatory release signing step

The plain Gradle release APK is **not distributable** to existing users (new key, no lineage proof yet). Always finish with:

```powershell
powershell -ExecutionPolicy Bypass -File SIGN_FINAL.ps1
```

This re-signs with the new key + v3 lineage (`--rotation-min-sdk-version 29`, v3-only) and produces `TRUE_NAIN_v2.0.2_FINAL.apk`. **Skipping this step breaks update-installs for existing users.**

### Verify before distributing

```
apksigner verify --verbose --print-certs TRUE_NAIN_v2.0.2_FINAL.apk
aapt dump badging TRUE_NAIN_v2.0.2_FINAL.apk
```

Must show: `Verifies`, v3 `true`, cert `328c0464…`, package `com.truenain.app`, versionCode 201.

---

## 🔑 Signing Keys

| Key | Status | Location |
|---|---|---|
| **NEW** (all builds) | ✅ active | `Desktop\Master G\Keys\true_nain_v2_release.jks` — alias `true_nain` |
| Old (COMPROMISED) | 🚫 never sign with it again — but **do not delete** (needed to extend lineage) | `Desktop\Master G\Keys\true_nain_release.jks` |

Full rotation record: **`Desktop\Master G\Keys\KEY_ROTATION.md`**.
Passwords live in `Master G\Keys\true_nain_v2_keystore.properties` — **never include keys or password files in any ZIP you share.**

Safe share-zip (excludes all secrets):

```powershell
robocopy <project> <staging> /E /XD keys .gradle .idea build /XF keystore.properties local.properties *.jks *.idsig
Compress-Archive <staging>\* share.zip
```

---

## 🗂 Project Map

| Path | What |
|---|---|
| `Desktop\True Nain APK\TRUE_NAIN_V2.0_ENGINE\` | **This project** — the v2.0 line (source of the current release) |
| `Desktop\True Nain APK\TRUE_NAIN_UPDATED_SOURCE_2026-10-21_CANDIDATE\` | 30-Oct fork — pristine, kept as reference |
| `Desktop\Master G\TRUE_NAIN_FINAL_LOCKED\` | Source of the verified v1.4.1 release |
| `Desktop\Master G\APK Version Wise\` | Archive of all past release APKs |
| `Desktop\Master G\Keys\` | Both keystores + lineage + `KEY_ROTATION.md` |
| `Desktop\TRUE_NAIN_v1.4.1_MASTER_BACKUP\` | Hash-verified backup of v1.4.1 source + artifacts |

---

## 📜 Version History

| Version | Code | SHA-256 (first 8) | Notes |
|---|---|---|---|
| 1.3.3 | 6 | `12700F2D` | First archived release (UI FINAL SOURCE) |
| 1.4.0 | 140 | `541100C8` | Performance fixes + Picture Rotate |
| 1.4.1 | 141 | `4ad9b00d` | **Last release on the old key** (key later compromised) |
| 2.0.1 | 201 | `3DC57B61` | Engine overhaul (superseded same-day) |
| **2.0.2** | **202** | `D938A5B3` | **Current** — + UI/UX overhaul, emulator-verified |

---

## ⚠️ Known Limitations (honest status)

- **No physical-device validation yet** — update-over-install (1.4.1 → 2.0.1), rapid-scrub feel, 4K/HEVC/Samsung behaviour, Perfetto/Macrobenchmark numbers are **CODE VERIFIED but NOT DEVICE VERIFIED**.
- Trim/Merge (`TrimActivity`, `ExactTrimTranscoder`) not re-audited in the 2.0 line.
- Extreme slow motion is frame duplication, not optical-flow interpolation.
- Lint: 0 errors, ~26 pre-existing warnings (i18n, accessibility) — cosmetic.

**Test checklist for a real phone:** update-install over 1.4.1 (data preserved) · open 1080p + 4K video · rapid scrub 100→700→1500 · 0.05x slow-motion · PHOTO ROTATE on Samsung portrait video · Gallery photo rotate · Customize Controls toggles.
