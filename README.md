# GC8 Digital Companion — Android

Android companion for the [GC8 Waveshare gauge firmware](https://github.com/JohnathanKong/gc8-gauge-display). **Primary objective: get real Google Maps turn guidance to the Waveshare display.** Native Kotlin app, initially validating Google's navigation notification data before adding Bluetooth.

## Read first

- [Master business requirements](https://github.com/JohnathanKong/gc8-gauge-display/blob/main/BUSINESS_REQUIREMENTS.md) — source of truth for full project scope and handoff.
- [Display firmware README](https://github.com/JohnathanKong/gc8-gauge-display/blob/main/README.md) — existing ESP32 build and flash instructions.

## Confirmed decisions

- The owner uses **Android** and navigates exclusively using **Google Maps**.
- Desired experience is a full moving map, but the first accepted deliverable is **next-turn arrow + distance + street name**.
- The ESP32 is the display; the existing Bluetooth head unit retains audio, music and call controls. **No media/audio/call controls** in this companion.
- A native **Kotlin** Android app will bridge supported Android information to the gauge through BLE.
- Google Maps does **not** provide a generic third-party API for extracting its live navigation state. Android notification access is a possible *experimental* source: do not promise exact field availability until tested on the actual phone.
- No scraping private app files, accessibility automation, or falsely claiming live guidance.

## Local environment

Suggested checkout location: `C:\Users\User\Projects\GC8-Gauge\gc8-gauge-display-android` (alongside `gc8-gauge-display`).

**Preferred environment: Docker Desktop + VS Code.** Build the native Kotlin app inside a container with JDK, Android command-line SDK and Gradle. Android Studio is **not required** for builds. The real Android phone will be used for testing; APK sideloading can be used initially, with optional ADB debugging later. Docker does not itself give direct USB/ADB access to a Windows-attached phone. Do not commit SDK paths, private keystores, device identifiers or tokens.

## Current status

- [x] GitHub repository created by owner.
- [x] Initial developer handoff and checklist committed.
- [x] Commit Docker-based Android SDK/JDK build environment (no Android Studio required).\n- [x] Build the Docker image successfully on the owner's Windows machine (owner confirmed 2026-10-21).
- [ ] Confirm phone make/model and Android version.
- [x] Commit minimal native Kotlin Android application and experimental Google Maps notification listener.\n- [x] Build Android APK successfully inside Docker on owner's machine (owner confirmed 2026-10-21).
- [x] Install and launch the app on the real Android phone (owner confirmed 2026-10-21).
- [x] Implement notification access settings shortcut and grant notification access on the phone (owner confirmed 2026-10-21).
- [x] Owner started Google Maps navigation (2026-10-21); notification contents not yet inspected.\n- [x] Commit in-app last-notification display, Refresh and Copy Debug Data controls (code committed; build/device test pending).\n- [ ] Build and install updated APK; verify notification capture on actual phone.
- [ ] Record actual navigation data available (direction/distance/street), conditions and limitations; avoid storing personal route history.
- [ ] Decide whether this source is sufficient; document fallback if not.
- [ ] Define versioned guidance message format shared with ESP32 firmware.
- [ ] Implement BLE transport with pairing/reconnect and head-unit coexistence.
- [ ] Render real next-turn guidance on Waveshare.
- [ ] Test route changes, reroutes, backgrounding, arrival, phone disconnect and reconnection.
- [ ] Investigate separately sourced full-map rendering **after** turn guidance succeeds.

## Workflow

Work **one meaningful step at a time** and keep instructions reproducible. Update this checklist and README after a feature is actually confirmed. Keep Android and ESP32 projects in separate repositories. Never mark device testing completed without direct user confirmation.

### Fresh chat handoff

> Continue my Android GC8 Digital Companion app. Read the README in `JohnathanKong/gc8-gauge-display-android` and the business requirements in `JohnathanKong/gc8-gauge-display`. The physical Waveshare LVGL dashboard is already running. I use Google Maps on Android and want real navigation instructions on the gauge; full map is a future goal, but arrow/distance/street is acceptable first. Start at the first unchecked milestone and proceed in one small step, committing source and updating docs. My Bluetooth head unit handles all audio and media controls.

## Docker build environment (Windows 11 + Docker Desktop)

The repo now contains `Dockerfile`, `compose.yaml`, `.dockerignore`, `.gitignore` and `docker-build.sh`.

- Base image: Eclipse Temurin JDK 17 (Ubuntu Jammy).
- Android command-line tools, SDK Platform 35, Build Tools 35.0.0 and platform-tools are installed **inside** the container.
- The working tree is bind-mounted at `/workspace`; Gradle's cache uses a persistent named volume.
- Android Studio and host-installed JDK/Android SDK are not required.
- **Owner confirmed the Docker environment and APK build worked on Windows on 2026-10-21.** This does not yet verify app installation, notification permission, Google Maps data capture, or BLE.

### First setup (PowerShell in repository root)

```powershell
docker compose build
docker compose run --rm android sdkmanager --version
```

The first build downloads the JDK base image and Android SDK; it can take a while and requires internet access. The second command should print the installed SDK manager version.

### Open an Android development shell

```powershell
docker compose run --rm android bash
```

The Kotlin project is committed. The container includes Gradle 8.9, so no local Gradle installation or wrapper is needed. Build the APK from the repository root with:

```powershell
docker compose run --rm android gradle --no-daemon assembleDebug
```

The resulting APK should be under `app/build/outputs/apk/debug/`. The **initial APK** was built and installed successfully (owner-confirmed). The **updated source** adds an on-screen capture display, Refresh, and Copy Debug Data, but **has not yet been built or device-tested**.

### Phone testing

Install the debug APK on the real Android phone. Initially use APK transfer/sideloading; Windows-to-container USB ADB forwarding is **not** automatically configured. ADB debugging can be set up separately if useful. Android notification access must be explicitly enabled by the owner in phone settings; Google Maps guidance fields are experimental until observed on the device.

### Next step — updated in-app diagnostics\n\nRun `git pull` then `docker compose run --rm android gradle --no-daemon assembleDebug`. Transfer `app/build/outputs/apk/debug/app-debug.apk` through Google Drive and install it over the existing app (same application ID; debug signing must match the previous build). Open GC8 Companion, start a Google Maps route, return to GC8 Companion and tap **Refresh captured data**. Use **Copy debug data** to share only after reviewing it for private addresses. The app stores only the most recent Google Maps notification in app-private preferences; it does not upload data or transmit BLE.\n\nIf Android rejects the update because the APK signing key differs, preserve the existing app until deciding whether to uninstall it; uninstalling may require re-granting notification access.

## Documentation verification policy

- Record the evidence for each completed checkbox (e.g. owner-confirmed Docker build vs observed physical phone behavior).
- Distinguish **committed**, **compiled**, **installed**, and **verified functioning**; these are not equivalent.
- Do not claim Google Maps notifications or Bluetooth work until validated on a real Android phone.
- Update this README and the master business requirements when scope, status or implementation decisions change.

### Phone verification — 2026-10-21

Owner reports the APK is installed and running, notification access was granted, and Google Maps is open. **Not yet confirmed:** active navigation, receipt of Google Maps notifications, extracted maneuver/distance/street fields, or BLE data transmission. Next test requires active turn-by-turn guidance and inspection of `GC8Maps` logcat records; opening Maps without starting navigation is not sufficient.

### Diagnostic UI change — 2026-10-21

Committed a locally stored last-Google-Maps-notification view with capture timestamp, title, text, big text and subtext; added Refresh and Copy buttons so USB/logcat is unnecessary. Also marked the protected notification listener service exported so Android can bind it. **Source committed, build and phone behavior not yet verified.** Data is in app-private SharedPreferences and copied to the clipboard only on user action. This is a diagnostic prototype, not a validated navigation parser; Google Maps notification fields may be missing or stale.

### Build failure and fix — 2026-10-21

The owner attempted to build the in-app diagnostics update and reported a Kotlin compile failure at `MainActivity.kt:49:102`: `Button` was passed where `Context` was required. Root cause: inside the button's `apply` block, `this` refers to the `Button`. Fixed `MapsCaptureStore.read(this)` to `MapsCaptureStore.read(this@MainActivity)` in commit `5f95bdb7`. **Fix is committed but rebuild success is not yet confirmed.** Re-run `git pull` and `docker compose run --rm android gradle --no-daemon assembleDebug`; no Docker image rebuild needed.
