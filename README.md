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

Use **Android Studio** for native Android app creation/build/debugging. Install the Android SDK and platform tools. Use the real Android phone for the notification experiments; emulator is optional. Do not commit SDK paths, private keystores, device identifiers or tokens.

## Current status

- [x] GitHub repository created by owner.
- [x] Initial developer handoff and checklist committed.
- [ ] Install and launch Android Studio on Windows.
- [ ] Confirm phone make/model and Android version.
- [ ] Generate a minimal Android Studio Kotlin application project and commit generated source/build files.
- [ ] Build and launch the app on the real phone.
- [ ] Implement clear permission onboarding for `NotificationListenerService`.
- [ ] Start Google Maps navigation and inspect **only the user's consented Google Maps notification fields**, locally on device.
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
