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
- [x] Confirm phone model: Samsung Galaxy Z Fold8 (owner confirmed); Android OS version not independently checked.
- [x] Commit minimal native Kotlin Android application and experimental Google Maps notification listener.\n- [x] Build Android APK successfully inside Docker on owner's machine (owner confirmed 2026-10-21).
- [x] Install and launch the app on the real Android phone (owner confirmed 2026-10-21).
- [x] Implement notification access settings shortcut and grant notification access on the phone (owner confirmed 2026-10-21).
- [x] Owner started Google Maps navigation (2026-10-21); notification contents not yet inspected.\n- [x] Commit in-app last-notification display, Refresh and Copy Debug Data controls (code committed; build/device test pending).\n- [x] Build and install updated APK; verify notification capture on actual phone (owner screenshot, 2026-10-21).
- [x] Record initial captured fields: Google Maps title contains road guidance; subtext contains ETA; text and bigText were empty in sample (owner screenshot, 2026-10-21).\n- [x] Observe Samsung ongoing-activity fields containing candidate distance and instruction text (owner captured `0 m` and `toward Castlemore Ave`; semantics not yet verified).\n- [ ] Validate distance updates with actual movement and decode whether any icon represents a maneuver.
- [ ] Decide whether this source is sufficient; document fallback if not.
- [ ] Define versioned guidance message format shared with ESP32 firmware.
- [x] Commit Android BLE permission-gated service discovery and GATT connection prototype (not built/device-tested).\n- [ ] Implement ESP32 BLE peripheral and verify Android connects on real hardware.\n- [ ] Implement versioned data writes, automatic reconnect and head-unit coexistence tests.
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

### Verified phone notification capture — 2026-10-21

Owner supplied screenshot of updated GC8 Companion on Samsung Android phone, while Google Maps navigation was active. App UI showed capture timestamp `2026-10-21 20:23:59`, notification title `Head toward Fred McLaren Blvd`, empty `Text` and `Big text`, and `Subtext: Arrive 9:02 p.m.`. Google Maps floating navigation window visibly showed a straight-ahead maneuver icon and route map, **but this does not establish that either graphic is accessible to the listener**. Verified: app update installed, local notification capture displayed in UI. Not verified: maneuver/distance data extraction, updates during route progress, BLE, ESP32 display. Do not equate the displayed ETA with distance to next turn. Next investigate notification extras and any icon fields without assuming Google Maps offers a stable navigation API.

### Destination entry and richer diagnostics — 2026-10-21

**Committed, not yet compiled or tested on the phone:** Destination text field opens a Google Maps `geo:0,0?q=...` search; user must select destination and start navigation in Maps. Optional **Ask voice assistant** button sends Android `ACTION_VOICE_COMMAND` and depends on installed/default assistant support (Google Assistant or Gemini not guaranteed; this is not an integrated voice destination parser). The diagnostics now list notification extras **key names and Java value types** and report presence of small/large icons, without serializing unknown objects. This does **not** imply a maneuver icon or next-turn distance is exposed. Rebuild via `git pull` and `docker compose run --rm android gradle --no-daemon assembleDebug`, then transfer the new APK through Google Drive. Validate search, assistant shortcut, and notification metadata separately on device; do not mark verified until owner confirms. Do not operate destination entry while driving.

### Samsung notification evidence and voice shortcut — 2026-10-21

Owner's Fold8 diagnostic copy from active Google Maps navigation showed `android.title = Head toward Castlemore Ave`, `android.subText = Arrive 8:39 p.m.`, empty `android.text`/`android.bigText`, and notification category `navigation`. Extra keys included Samsung `android.ongoingActivityNoti.*` fields (`primaryInfo`, `secondaryInfo`, `nowbarPrimaryInfo`, `nowbarSecondaryInfo`, `chipExpandedText`) plus icon objects, progress points/segments and a tracker icon. **Their values and semantics are not yet verified.** New code now reads only selected Samsung `String` fields into the local diagnostics view (committed, **not yet compiled or phone-tested**). Progress metadata and icon presence do not prove a maneuver or distance is accessible. User reported the generic `ACTION_VOICE_COMMAND` shortcut opened Bixby; do not describe it as a Gemini-specific shortcut. Plan to replace generic voice launch with a user-controlled speech-to-destination path; not yet implemented. User wants Gemini/Google Maps, not Bixby.

### Fold8 Samsung ongoing-activity values — 2026-10-21

Owner confirmed the updated APK ran and shared active Google Maps navigation diagnostics: `android.ongoingActivityNoti.chipExpandedText`, `nowbarPrimaryInfo` and `primaryInfo` each contained `0 m`; `nowbarSecondaryInfo` and `secondaryInfo` each contained `toward Castlemore Ave`. Notification title was `Head toward Castlemore Ave`, subtext `Arrive 8:43 p.m.`. This is **direct evidence of candidate distance and instruction text**, not yet proof that distance changes as navigation progresses or that any icon is the next maneuver. Icon fields are present but not decoded. The output displayed literal `\\n` delimiters; a source fix was committed but **has not yet been built/tested**. Next verify dynamic distance updates in a safe driving/passenger test, and then investigate icon content. Do not interact with the phone while driving.

### BLE bring-up started — 2026-10-21

Android source now includes `GaugeBleClient.kt` and a **Connect to GC8 display (BLE)** button. It requests Android 12+ Nearby Devices permissions, scans 12 seconds for a peripheral advertising service UUID `a8e31c40-6b8f-4b67-a4f0-4a0b75000001`, connects by GATT, and looks for write characteristic `a8e31c40-6b8f-4b67-a4f0-4a0b75000002`. **No ESP32 BLE peripheral firmware is committed yet; current Waveshare firmware will not advertise this service, so scanning cannot find it. No data is transmitted yet.** This is a transport skeleton, not a working end-to-end connection. Source has not been compiled or phone-tested. UUIDs are provisional and must match display firmware. Do not mark BLE functional until hardware tests. The Fold8 is in a basement; `0 m` may reflect poor GPS location, not a verified next-turn distance.

### First real BLE connection confirmed — 2026-10-21

Owner supplied screenshot of the Galaxy Z Fold8 GC8 Companion displaying `BLE: GC8 BLE connected; write characteristic ready.` after tapping the BLE connection button while the development Waveshare was running the newly flashed firmware. This **verifies BLE service discovery, GATT connection, and write-characteristic discovery on real hardware**. It does **not** yet verify a successful characteristic write, a received message on ESP32, on-screen Bluetooth state, or navigation rendering. Screenshot also showed `No Google Maps notification captured yet` in that session, which is independent of BLE connectivity. Next milestone: implement and verify a harmless test write from Android, ESP32 reception, then on-screen indication.

### End-to-end BLE test message — source committed 2026-10-22 (NOT YET TESTED)

Both repositories now contain the first data-transfer test implementation. Android adds **Send test navigation to Waveshare**, requests BLE MTU 185 before GATT discovery, and writes `GC8|1|RIGHT|250 m|Main St` with write-response completion status. ESP32 accepts only a bounded, versioned, printable ASCII message with maneuver RIGHT/LEFT/STRAIGHT, stores a thread-safe snapshot, and refreshes LVGL labels: `PHONE: WAITING/CONNECTED`, `NAV: RIGHT 250 m`, and `Main St` when received; otherwise `NAV: NO DATA` / `ECU: OFFLINE`. The ESP32 is **not** extracting ECU telemetry or actual Google Maps navigation, and the Android write callback confirms GATT completion, not necessarily that a person observed the screen. **Both new code paths are committed but uncompiled/unflashed/uninstalled and not device-verified.** First build/flash ESP32 from ESP-IDF terminal (`git pull`, `build.bat`, flash development board only). Then build Android (`git pull`, `docker compose run --rm android gradle --no-daemon assembleDebug`), transfer APK via Google Drive, connect BLE, tap Send test navigation, and visually confirm screen. Stop and share compiler errors if either build fails. This test endpoint is unauthenticated and should not carry private navigation destinations or vehicle-control commands. Real navigation streaming, reconnect, authentication, and reliable device-state reporting remain future work.

### 2026-10-22 BLE data-transfer build evidence

Owner provided ESP-IDF terminal screenshot showing `Successfully created ESP32-S3 image` and `Project build complete` for the newly updated Waveshare firmware with BLE test navigation UI; ~89% of application partition remains free. **Compiled successfully; flashing this revision and visually verifying `PHONE: WAITING` / test navigation are still pending.** The Android data-write revision is not yet reported built or installed. Next flash development Waveshare and verify visible status, then install updated Android APK and test write.

## Agreed product design direction — 2026-10-22

**Status: approved design direction, not yet implemented as the production UI.** The owner approved the circular, minimal, black-background navigation-first concept for the Waveshare ESP32-S3-Touch-AMOLED-1.75C. The sketch is inspiration, **not a literal layout or styling specification**.

- **Home/default page: Navigation.** Give the large next-maneuver arrow the strongest emphasis, followed by distance to maneuver and street name. Allocate roughly **70–75% of usable display space** to navigation; preserve legibility at a quick glance. Avoid thick decorative borders/rings that waste pixels. Start on this page at boot.
- **Persistent lower instrument: Wideband AFR.** Reserve the lower ~25–30% for a prominent numeric AFR value and a subtle coloured accent/arc/bar. AFR must remain visible **on every page**, including phone and vehicle pages. Display `--.-` when there is no verified sensor feed; never fabricate a live reading. Do not colour-code danger/normal states without validated sensor readings and context-aware thresholds (stoichiometric cruising versus richer boosted operation). Avoid flashing/changing the entire screen background with normal AFR fluctuations.
- **Horizontal page navigation:** From the home page, swipe **right for Phone**, swipe **left for Vehicle**. Phone page can prioritize favourites/recent calls; a full keypad is exploratory and may be unsuitable for the small display, especially while driving. Vehicle page will eventually show ECU/sensor information; prioritize useful metrics later. **Boost is explicitly deferred** rather than included in the initial home screen.
- **Incoming call UI:** A compact top-down overlay with answer/decline controls is the desired interaction; it must not permanently replace the selected page or hide critical warnings. Android call-control access and coexistence with the existing Bluetooth audio head unit remain **unverified technical dependencies**, not promised functionality.
- **Navigation source:** Google Maps on the Android phone supplies candidate instruction/distance data; the Waveshare displays it over BLE. Distance semantics and maneuver icon extraction still need validation. Maintain clear phone/ECU connection states. Do not imply the current BLE test message is live Google Maps guidance.
- **Future map view:** Investigate a miniature moving map **later**, only if technically feasible and useful at this display size. A map consumes valuable screen space; it must not displace the navigation-first experience or the persistent AFR reading. Treat as a separate design/technical investigation, **not part of the initial implementation or a commitment**.
- **Visual language:** High-contrast white navigation graphics and readable typography on deep black, restrained accents, natural circular composition, minimal chrome. The approved concept is directional guidance, not a pixel-perfect mockup.

**Implementation sequence:** first verify Android test-message write and ESP32 display on physical hardware; then implement the approved navigation-first layout with persistent AFR placeholder; then connect verified Google Maps updates; add real wideband hardware feed and call/vehicle features only after their respective integrations are validated. No final UI build or screenshot has yet been approved on hardware.

### Navigation-first screen implementation — 2026-10-22

**Firmware source committed, NOT yet built/flashed/verified on hardware.** The ESP32 `main/main.cpp` has been replaced with a circular-screen-aware black navigation-first home view: top phone connection status; large graphical left/right/straight maneuver indicator; next-turn distance and road; divider; permanent lower wideband AFR section showing `--.-` and `NO SENSOR` until real hardware exists. The previous four fake telemetry tiles are removed. BLE snapshot updates the navigation fields and phone connection indicator; no new BLE transport changes are needed for the existing test message `GC8|1|RIGHT|250 m|Main St`. The Android companion already contains a **Send test navigation to Waveshare** button, but **automatic live Google Maps-to-BLE streaming is NOT implemented**. No calibrated AFR colour thresholds, boost, swipe pages, phone call controls or moving map are implemented. The graphical arrow is a first-pass implementation and needs review on the real AMOLED before considering its styling final.

**Next:** In the ESP-IDF terminal run `git pull` and `build.bat`. If successful, flash only the development Waveshare, verify the navigation-first screen, then connect the Fold8 using the updated Android APK and press **Send test navigation to Waveshare**. Confirm the screen shows the arrow, `250 m`, `Main St` and `PHONE CONNECTED`. Stop and share build errors rather than flashing a failed build.

### Destination and notification shade direction — 2026-10-22

The owner wants a standard **top pull-down notification shade** eventually (Android-like interaction, incoming calls and notifications). **Design requirement recorded only; no gesture handling, shade, notifications or call controls implemented yet.** Preserve a top-edge gesture zone when implementing touch navigation and ensure critical AFR display remains visible/appropriately prioritized.

The navigation-first home screen now includes a small muted **`TO: <destination>`** line immediately below the next-turn street name and above the permanent AFR region. It shows `TO: --` without route data and `TO: Not provided` when a route message lacks destination. Firmware's existing `GC8|1|MANEUVER|DISTANCE|STREET` test format now accepts an **optional sixth pipe-separated printable ASCII destination field** (max 47 characters), preserving compatibility with older five-field messages. Android's **test button** now sends `GC8|1|RIGHT|250 m|Main St|Apex Motoring`. **This is illustrative test data, not an actual navigation destination from Google Maps.** Google Maps notification capture does not yet reliably provide the end destination; real integration must retain the destination entered in the Android companion and send it separately with navigation updates. Current BLE messages remain bounded at 100 bytes; long names require truncation/encoding strategy later. All changes committed; **neither new firmware nor new APK has been built, flashed or verified**. Review layout on physical round AMOLED after flashing, especially destination truncation and navigation/AFR spacing.

### 2026-10-22 — approved arrow pass + AFR demo (committed, NOT built/tested)
- Regenerated embedded left/right/straight A8 navigation arrow assets with 4x supersampling, thinner shaft, shorter tail, curved bend and single pointed head. These are firmware-ready approximations of the approved visual reference; **verify appearance on physical screen**.
- Removed the defective LVGL AFR arc entirely (its knob appeared as a bright cyan dot and the curve overlapped text). Retained a clean divider and permanent AFR section. A future calibrated arc remains separate work.
- Extended the existing optional-destination navigation test protocol with an **optional seventh field**, e.g. `GC8|1|RIGHT|250 m|Main St|Apex Motoring|DEMO:14.7`. The parser accepts only a `DEMO:`-prefixed numeric test value. Five- and six-field navigation messages still work. The display renders `14.7` plus **DEMO ONLY**, never a misleading live sensor status. When demo data is absent or BLE disconnects, it displays `--.-` / **NO SENSOR**. No actual AEM sensor connection exists.
- The Android **Send test navigation to Waveshare** button sends the seven-field message in **one BLE write**, so no additional BLE characteristic or sequencing is required. This is a fixed test value, **not a live or calibrated AFR reading**.
- Firmware and Android changes are committed but **not independently compiled, flashed, or tested**. Rebuild BOTH repositories, flash development Waveshare and install updated APK; reconnect and press the test button. Check the arrow, destination, AFR value and DEMO ONLY status. Do not flash if build fails.

### 2026-10-22 — first automatic Google Maps notification → BLE pipeline (source committed; UNBUILT/UNTESTED)
- Android `MapsNotificationListener` now attempts to parse recognizable LEFT/RIGHT/STRAIGHT instructions, a distance token from Samsung's navigation notification fields, and a street name from the instruction, and forwards these through the existing BLE write characteristic **without pressing the test button**. Unsupported/missing fields are ignored rather than fabricated. This is **experimental**: Google Maps notification content and Samsung keys are undocumented and can change. Distance extraction is intentionally conservative and may fail with punctuation or different locales.
- `MainActivity` saves the destination entered before opening Google Maps. The notification listener reuses this user-entered destination. A destination set directly inside Maps is not known to the companion and will not be guessed.
- The BLE client uses a process-local active instance and main-thread serialization, and only sends while that instance remains alive and BLE is connected. **This is not yet a robust background service**: Android may kill the process or disconnect BLE while Maps is foregrounded. Queuing/reconnect, notification removal and stale-route clearing are future work.
- Live messages omit the simulated AFR field, so the firmware shows **NO SENSOR** for real Maps updates. The manual test button still sends `DEMO:14.7`.
- No APK build, real navigation drive, or end-to-end verification has been performed. Build Android using `docker compose run --rm android gradle --no-daemon assembleDebug`; install, grant notification access, connect to GC8, enter destination through companion, start Maps and test on foot/passenger (do not operate while driving). Check debug notification fields if no update arrives.

### 2026-10-22 — consolidated first-road-test candidate (committed; BUILD/DRIVE NOT VERIFIED)
Owner chose to **retain the official Google Maps phone app** (not replace it with Navigation SDK). This revision combines:
- Maps notification extraction of supported turn/road/positive distance; Samsung ongoing-activity candidate fields; duplicate suppression; parser status visible in **Refresh captured data** and **Copy debug data**.
- Conservative handling of `0 m` and missing information: do **not** fabricate an upcoming turn. A missing notification field may prevent display updates.
- Automatic BLE forwarding while companion process/client remains alive; one latest queued update while a BLE write is pending; limited disconnect reconnect attempts; explicit `GC8|1|CLEAR` when Maps navigation notification is removed and no other Maps notification remains.
- Final destination saved when entered through GC8 Companion. Routes started directly in Maps may show destination as **Not provided**.
- Manual demo test retained separately and labeled `DEMO ONLY`; live notification messages carry no AFR measurement.
- ESP32 companion firmware must be updated **together** to support CLEAR; previous navigation payloads remain compatible.
- **Known limitations:** Google Maps notification fields are undocumented; may be stale or not update at every turn. Roundabouts, U-turns, exits and unsupported instructions are not represented. Clearing is notification-based and may misinterpret notification changes. BLE client is activity-owned, not a dedicated Android foreground service; background survival and automatic reconnect are not guaranteed. No real navigation source access via supported Maps SDK. No build, phone install, or drive test performed on this revision.

**One consolidated test after BOTH builds:** update/flash Waveshare firmware; build/install APK; enable notification listener; connect BLE; verify manual test `RIGHT / 250 m / Main St / DEMO ONLY 14.7`; start Google Maps navigation from GC8 Companion while parked and inspect **Bridge:** status. If it parses, test a short familiar route with a passenger observing the screen; verify changes, reroute, finish and Bluetooth recovery. If the bridge reports missing distance or unsupported maneuver, **stop and capture diagnostic text** rather than assuming navigation is reliable. Never rely on experimental gauge instructions for driving decisions; Google Maps remains the authoritative display/audio. Do not operate the app while driving.

### 2026-10-22 — drive recorder and notification watchdog (COMMITTED; not built/tested)
- Added local, timestamped `DriveLog` (bounded to roughly 512 KiB, trims older events), with `Export drive diagnostics` and `Clear drive diagnostics` in the Android app. Export uses a restricted AndroidX FileProvider and standard Android share sheet. **Logs can include navigation destinations and road names; review before sharing.** No automatic upload.
- Records Maps notification fields, parsing decisions, forwarding, BLE status, queued/deduplicated writes, BLE write callbacks, and notification removal. The notification listener checks every 30 seconds for gaps since the last Maps event and logs `WATCHDOG` warnings when at least 30 seconds have elapsed. This is a **freshness check, not Google Maps polling**. Google Maps controls when notifications change; polling Android's active notification does not force fresh route progress.
- Important limitation: GATT write completion is not a display-render acknowledgement; ESP32-side serial logs are not yet exported to the Android drive log. BLE is still Activity-owned and may not survive Android background lifecycle. The watchdog only runs while the notification listener process remains alive, and a missing event cannot establish whether Maps has internally changed its state.
- **No build or physical-device verification performed.** The Android Gradle build now includes `androidx.core:core:1.15.0` for safe log export. After build/install, clear logs before the test, then export after a parked/short passenger-observed navigation test. Check that the exported text contains `MAPS_NOTIFICATION`, `PARSE`, `FORWARD`, `BLE_SEND`, and `BLE_WRITE_RESULT` or the relevant missing-stage warning. Do not operate the app while driving.

### 2026-10-22 — selected-destination investigation (Android only; not yet built/tested)
- New optional `MapsDestinationAccessibility` service, restricted to `com.google.android.apps.maps`. It reads visible UI text and descriptions from accessibility events and saves the latest text tree in local app preferences. It also records compact `MAPS_ACCESSIBILITY` diagnostic entries in the drive log. It does **not** perform gestures, read other apps, or automatically choose a destination.
- In GC8 Companion, tap **Enable Maps destination reader (Accessibility)** and manually enable **GC8 Maps destination reader** in Android Accessibility settings. Search for a broad term (e.g., chicken), select a specific place in Google Maps, start navigation, and then use **Copy destination screen diagnostics** or **Export drive diagnostics**. Check whether the actual selected place and address are accessible; no assumptions based on the search query.
- The existing maneuver/distance/street parser and BLE transport are unchanged. The unverified original search query is no longer sent as the destination; destination stays empty until we validate an actual selected place.
- Accessibility text may include sensitive addresses; review before sharing. Samsung/Google Maps accessibility structure may change or omit the selected destination. **This code is committed but has not been compiled or phone-tested.**

### 2026-10-22 — selected destination handoff implementation (unverified)
- Accessibility observes selected-place details: the explicit `Address:` description and `Directions to ...` label. It stores a candidate only when both refer to the same street. Search queries are never treated as destinations.
- Accessibility listens for a Google Maps `Start` click and confirms a recent candidate only if the navigation step-cue screen appears within 20 seconds. Candidates expire after two minutes for Start association. The confirmed address is forwarded in existing notification-driven BLE payloads; route removal clears it.
- No change to maneuver/street/distance parsing or firmware protocol. The Waveshare destination field currently has limited width and may truncate full addresses. Android BLE sanitization also caps the destination at 47 characters.
- **Not compiled or hardware-tested.** Accessibility click events and Google Maps UI structure are not guaranteed. If `DEST_CANDIDATE` appears but `DEST_START` or `DEST_CONFIRMED` does not, export drive diagnostics after parking; the destination remains blank rather than guessing. Also test a generic search with multiple results, switching between places, and ending navigation. Do not interact with the companion while driving.

### 2026-10-23 — diagnosed destination confirmation failure from actual drive log

Actual log showed `DEST_CANDIDATE` for Mackenzie Health with its full address, followed by `DEST_START`, but no `DEST_CONFIRMED`. Samsung Maps navigation was exposing `navigation_time_remaining_label` alongside `Close navigation`, while the accessibility reader only recognized `navigation_stepcuefirstline_textbox`. Notification-to-BLE still forwarded accurate maneuver/distance but with empty destination. The log proves destination *capture* and Start detection worked; the missing link was the transition recognition.

Fix:
- Recognize Samsung's active navigation chrome using a paired arrival-time + Close-navigation pattern (also legacy stepcue and paired navigation FAB patterns). Process the root on the Start-click event itself, because the navigation screen can appear before the click event is handled.
- Stop refreshing an unchanged destination candidate every five seconds; the old behavior could clear the armed Start or recently confirmed destination.
- Consume a candidate once it is confirmed, require a freshly selected place for the next route, and clear all retained destination state when Google Maps returns to its home UI (search omnibox + Explore). Do not reuse the old confirmed destination if Start occurs without a fresh candidate.
- Destination confirmation signals the existing BLE bridge to resend immediately using its last valid maneuver, instead of waiting for the next Maps notification.
- **No changes to notification maneuver/distance/street parsing, BLE protocol, firmware, or display rendering.**

Current revision is **not Android-compiled or device-tested**. First verify that `DEST_CANDIDATE`, `DEST_START`, `DEST_CONFIRMED`, and `DEST_FORWARD` appear in that order for Mackenzie Health. Then stop the route, return to Maps home, select a different place, and confirm the old destination does not reappear. The sample log screenshot's navigation indicators satisfy the new fallback predicate in a local text check; this is not a replacement for a Fold8 test.

### 2026-10-23 — fix repeated uninstall/reinstall for Docker-built debug APKs

**Cause:** The app was built with `docker compose run --rm android ...`. Android Gradle's default debug signing key lives in `/home/android/.android/debug.keystore`, but only `/home/android/.gradle` was mounted persistently. Every ephemeral build container could generate a new debug signer, so Android refused to update the existing app over the old APK.

**Fix:** `compose.yaml` now mounts a named volume `android-debug-signing` at `/home/android/.android`. The `Dockerfile` creates this directory with the correct ownership before the volume is mounted. Build this Docker image once with `docker compose build android`, then continue using `docker compose run --rm android gradle --no-daemon assembleDebug`. Future builds using the same Docker Compose project and signing volume will use the same key and should install as updates.

**Migration:** APKs created before this fix may have incompatible signers. The owner may need to uninstall the already installed APK **once** to install the newly signed build; this clears app data, and notification/Accessibility/BLE permissions may need to be reauthorized. After that, keep the signing volume; do not run `docker compose down -v`, remove `android-debug-signing`, or `docker volume prune` when expecting future updates to preserve signing identity. Do not check a private keystore into Git. Same `applicationId` (`io.toyboxracing.gc8companion`) is retained. A stable signing key is necessary but not a guarantee against unrelated installation failures.

### 2026-10-23 17:11 — compact Google Maps place card did not produce a candidate

The latest real device log showed Google Maps displaying a **Mackenzie Health** place-details card with an accessible street-view thumbnail description, matching business title and "Driving mode: 30 min". The previous destination parser only accepted a matching "Directions to ..." plus "Address: ..." pair. As a result no `DEST_CANDIDATE` was recorded; the subsequent Start event logged `DEST_IGNORED`. The previously fixed navigation chrome detector then correctly logged `DEST_NAV_UI`, but it had no candidate to confirm.

**Fix (code committed, device build/test pending):** `MapsDestinationAccessibility` now recognizes either (a) the previously verified full-address details or (b) a selected place sheet containing three mutually corroborating signals: `View Street view imagery for NAME`, `text=NAME desc=NAME`, and `Driving mode: ...`. A generic text search or a result without those corroborating signals cannot be selected by this rule. `MapsDestinationCapture` now permits a verified selected **place name** without a postal address; if Maps later reveals an address for the same candidate, it is enriched without resetting Start. The BLE destination field displays the verified selected business name (more legible on the round display) rather than a long postal address. Existing notification maneuver/distance/street extraction and BLE protocol are unchanged. This fallback predicate was checked against the first eight accessible nodes in the actual 17:11:01 device log and matched all three expected markers; **no device validation or Android build is claimed**.

Next test: install updated APK using the persistent signing volume, select Mackenzie Health as before, tap Start, and verify `DEST_CANDIDATE` -> `DEST_START` -> `DEST_CONFIRMED` -> `DEST_FORWARD`. Repeat with a different selected business to make sure stale destination data is not reused. If the selected place has no business label, the UI may correctly remain "Not provided" rather than guessing.
