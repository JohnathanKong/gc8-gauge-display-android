# GC8 Garage Door Button — Working Setup and Recovery Guide

**Last updated:** 2026-10-24  
**Status:** End-to-end operation reported working by owner after correcting the SmartThings garage-door state. The **Garage Open** routine is confirmed from an owner screenshot; **Garage Close** is documented as the complementary intended configuration (its exact settings were not separately pictured). This is a home-automation convenience, **not** a verified safety-critical/security-hardened controller.

## 1. Names, apps, and responsibilities

| Name | Where it lives | What it does |
| --- | --- | --- |
| Upper **PWR** button | Waveshare ESP32-S3-Touch-AMOLED-1.75C | Physical request to operate the garage; **not** the lower BOOT key |
| **GC8 Companion** | Samsung Galaxy Z Fold8 Android app | Receives BLE button event and posts an Android notification |
| **Garage Trigger** (notification text) | GC8 Companion setting | **Text to match**, saved in the phone app; currently set by the owner to `Garage Trigger` |
| **GC8 notification bridge** (descriptive name, not the owner's confirmed routine name) | **Samsung Settings → Modes and Routines → Routines** | Notification received from GC8 Companion → operate SmartThings virtual switch |
| **Garage Trigger** (virtual switch) | **SmartThings → Devices** | An actual ON/OFF virtual device; distinct from the similarly named notification text |
| **Garage Open** | **SmartThings → Routines** | When switch is ON **and** real garage is Closed, command Open and reset switch OFF |
| **Garage Close** | **SmartThings → Routines** | When switch is ON **and** real garage is Open, command Close and reset switch OFF |
| **Garage Door Opener** | SmartThings device (Meross-backed) | Supplies **actual reported door state** and accepts Open/Close |

**Critical distinction:** Merely entering `Garage Trigger` into the Android text box does **not** select a SmartThings device. The Samsung phone routine must explicitly select the SmartThings virtual device in its **THEN** action. There are two different Samsung products with similarly named routine editors: **Samsung Modes and Routines** (phone settings; can listen for a notification) and **SmartThings Routines** (home automation; responds to virtual-device/garage states). Never confuse them.

## 2. End-to-end signal path

```text
Short press UPPER PWR on Waveshare
  -> ESP32 detects upper PWR via GPIO3 (lower BOOT is untouched)
  -> ESP32 sends BLE notify: GC8|1|GARAGE|PRESS
  -> Fold8 GC8 Companion receives event (requires active BLE link + armed garage button)
  -> Companion posts Android notification titled: Garage Trigger
  -> Samsung Settings > Modes and Routines:
       IF notification received from GC8 Companion containing "Garage Trigger"
       THEN SmartThings > virtual device "Garage Trigger" > Turn ON
  -> SmartThings evaluates two separate mutually exclusive routines:
       Door reported CLOSED + Garage Trigger ON -> OPEN door -> switch OFF
       Door reported OPEN   + Garage Trigger ON -> CLOSE door -> switch OFF
  -> Virtual switch is back OFF, ready for the next physical press
```

The app does **not** use Meross API, SmartThings REST tokens, direct Wi-Fi commands, or a garage-door state sensor. SmartThings supplies the current door state and runs the correct Open/Close action. The notification and the virtual switch share the text `Garage Trigger` **by configuration**, not via an automatic lookup.

## 3. Waveshare ESP32 firmware (physical button and BLE)

- Hardware is the **round 1.75C** model. Firmware reads the **upper PWR sense on GPIO3**. This corrects an older, **incorrect** assumption about TCA9554 on the different non-C board. The **lower BOOT/GPIO0** is not the garage button; do not use it.
- A brief press-and-release is required. Source debounce: three 35 ms consistent samples; measured press 75–1200 ms; 6-second cooldown. Do **not** hold the power key; a long hold can invoke the board's own power behavior.
- Existing GC8 BLE service UUID: `a8e31c40-6b8f-4b67-a4f0-4a0b75000001`. Android uses the write characteristic ending `0002` and subscribes to the garage notify characteristic ending `0003`.
- When Android is subscribed, pressing PWR emits `GC8|1|GARAGE|PRESS`. The gauge may show `GARAGE REQUESTED` and then `GARAGE SIGNAL SENT`. **Neither message verifies a real door movement.** `GARAGE SIGNAL SENT` means Android reported notification submission.
- On startup, serial log should contain `gc8_ble: Advertising GC8 service`. The past `NimBLE: ble_gatts_count_resources rc=3` / `gc8_ble: GATT registration failed: 3` defect was fixed by adding a GATT callback to the notify-only characteristic in firmware commit [ea90d27](https://github.com/JohnathanKong/gc8-gauge-display/commit/ea90d27e38da9395c748ae26c07dc35afd4d8592).
- The label `BLE WAITING` means **not currently connected**. It does not prove whether advertising started; inspect the serial log when in doubt.

Source: [firmware main/garage_button.cpp](https://github.com/JohnathanKong/gc8-gauge-display/blob/main/main/garage_button.cpp) and [main/gc8_ble.cpp](https://github.com/JohnathanKong/gc8-gauge-display/blob/main/main/gc8_ble.cpp).

## 4. GC8 Companion Android setup

1. Install the current GC8 Companion APK on the Fold8. Enable Bluetooth and open the app.
2. Tap **Connect to GC8 display (BLE)**. A successful connection reports that the GC8 write characteristic is ready. Make sure the Waveshare is powered and BLE is advertising.
3. Tap **Allow GC8 notifications** and grant notification-posting permission. This is **different** from Google Maps notification-listener access.
4. The **Notification trigger text** field must read exactly **`Garage Trigger`** for the current Samsung routine. This controls the notification title; **it is not a SmartThings device-name picker**.
5. To change that field later: tap **Disable garage button**, edit text, **Save garage trigger text**, update the Samsung IF notification keyword to the same text, then **Enable garage button for connected Waveshare** again.
6. Tap **Enable garage button for connected Waveshare** after BLE is connected and notification permissions are granted. Explicitly accept the confirmation. The app remembers the currently connected BLE device address; it also offers **Disable garage button**.

The previously hardcoded default is `GC8_GARAGE_TRIGGER` **until explicitly changed and saved**. The working configuration uses `Garage Trigger`, which the owner entered. If an old APK is installed, check the actual notification text and match the Samsung IF condition accordingly.

The app uses a 5-second button-event software lockout and a further 6-second notification lockout, in addition to firmware's 6-second PWR cooldown. A new Android notification is generated per accepted press; the short-lived notification is automatically cancelled after ~8 seconds. These are duplicate-prevention measures, **not** door-state confirmation.

Source: [GarageRoutineTrigger.kt](https://github.com/JohnathanKong/gc8-gauge-display-android/blob/main/app/src/main/java/io/toyboxracing/gc8companion/GarageRoutineTrigger.kt), [GaugeBleClient.kt](https://github.com/JohnathanKong/gc8-gauge-display-android/blob/main/app/src/main/java/io/toyboxracing/gc8companion/GaugeBleClient.kt).

## 5. Samsung **Modes and Routines**: the essential phone-side bridge

**This step was initially missing.** Create **one** Samsung phone routine (NOT a SmartThings routine):

1. On Fold8 open **Settings → Modes and Routines → Routines → +**.
2. **IF:** **Notification received**.
   - **App:** `GC8 Companion`.
   - **Specific keyword / contains:** `Garage Trigger` (exactly the notification text saved in Companion).
3. **THEN:** **SmartThings** → choose the **virtual switch device named `Garage Trigger`** → **Turn ON**.
4. Save and enable the phone routine.

**Use ON, not OFF or ON-and-OFF.** This routine merely raises the virtual switch; it does not itself open/close the real door. It is **not sufficient** to repeatedly turn an already-ON switch ON again. The SmartThings garage routines below **must reset the switch OFF** after every successful action so the next PWR press causes a fresh OFF → ON transition.

If Samsung's history says **Ran**, that confirms the phone routine started, **not** necessarily that the SmartThings THEN action succeeded. A past `Couldn't perform some actions because another operation is preventing it` error was seen during troubleshooting; its exact cause was not isolated. Check its individual action history and live switch state instead of attributing it automatically to the already-ON switch.

## 6. SmartThings **Routines**: two mutually exclusive door actions

Set the IF combination to **When all conditions below are met**, NOT **When any condition below is met**. All conditions must be true, and **both** garage routines must require virtual switch **ON**. The user confirmed the overall setup works after correcting the garage's reported state.

### A. Garage Open (owner screenshot confirmed)

**IF — all conditions:**

| Device | Condition |
| --- | --- |
| `Garage Trigger` (virtual switch) | **On** |
| `Garage Door Opener` | **Door: Closed** |

**THEN — actions in the displayed order:**

1. `Garage Door Opener` → **Door: Open**
2. `Garage Trigger` → **Turn off**

The owner supplied a screenshot showing exactly these two IF conditions and two THEN actions.

### B. Garage Close (complementary configuration; verify its screen)

**IF — all conditions:**

| Device | Condition |
| --- | --- |
| `Garage Trigger` (virtual switch) | **On** |
| `Garage Door Opener` | **Door: Open** |

**THEN — recommended mirror of the Open routine:**

1. `Garage Door Opener` → **Door: Close**
2. `Garage Trigger` → **Turn off**

The owner reported a working pair of **Garage Open**/**Garage Close** routines and confirmed resetting the switch to OFF after the garage action. We did **not** receive a screenshot proving every field of the **Close** routine; check this mirror against the actual SmartThings configuration rather than treating it as independently inspected.

**Do not use** ON-or-OFF as the switch condition, or ANY-condition logic. Otherwise either a switch reset or a mismatched garage state can issue an unintended door command. While the door is opening/closing, neither the fully Closed nor fully Open condition should match; don't keep pressing the button while the door is moving. There is no confirmation from the firmware that the door reached a desired position.

### Why the switch resets

The switch is a **momentary trigger implemented using a regular ON/OFF virtual switch**, rather than a switch that represents the real door position:

- **OFF = idle/ready**, not "garage closed."
- **ON = a request arrived**, not "garage open."
- After the door action, return switch **OFF**. Without this, later **ON → ON** presses may cause no state-change event and appear to do nothing.
- It is the **Meross-backed Garage Door Opener status**, not the virtual switch, that determines whether to open or close.

## 7. Safe verification, in order

Before triggering physical movement, verify the garage entryway is unobstructed, the door is in view, and no other person/device is operating it. Work one stage at a time.

1. **Check actual reported state first** in SmartThings → Devices → `Garage Door Opener`. Physically compare door position against **Open/Closed**. **A wrong reported door state prevented the user's routine from running**; fixing that state resolved the issue.
2. In SmartThings → Devices, find the separate virtual switch `Garage Trigger`. Inspect the **actual device tile**, not the SmartThings **routine definition**. The routine's IF text "On" is a configured condition, **not a live switch indicator**.
3. For a non-actuating bridge test, temporarily disable the **Garage Open** and **Garage Close** SmartThings routines, manually turn the virtual switch OFF, connect/arm GC8 Companion, then tap Waveshare upper PWR once. Confirm the Android notification occurred and SmartThings virtual switch went **OFF → ON**. Manually set the switch OFF before re-enabling the garage routines.
4. For the end-to-end owner-confirmed flow: ensure real door status is **Closed**, virtual switch **OFF**, both SmartThings routines enabled, and Samsung phone routine enabled. With the door in sight, tap PWR once. Expect **Garage Open** to operate and virtual switch to return **OFF**.
5. Once physically **Open** and SmartThings **reports Open**, verify virtual switch **OFF** and do one more short press. Expect **Garage Close** and virtual switch **OFF** again.
6. **Stop** if SmartThings's state is wrong, the door moves unexpectedly, or the virtual switch does not reset; do not keep sending commands.

You can manually test the virtual switch in **SmartThings → Devices → Garage Trigger → its device switch control**, not by tapping the IF condition on a routine details screen. Turning it ON with active garage routines **may move the real door**.

## 8. Troubleshooting map

| Symptom | First thing to check |
| --- | --- |
| Gauge says **BLE WAITING**; Android reports "GC8 not found; display firmware must advertise the service UUID" | Firmware serial monitor for `gc8_ble: Advertising GC8 service`; the earlier GATT registration rc=3 failure blocks advertising |
| BLE connects, but upper PWR shows **GARAGE: PHONE OFFLINE** | Phone subscribed to the garage notify characteristic; reconnect in GC8 Companion and inspect `GARAGE_SUBSCRIBE` logs |
| PWR shows **GARAGE NOT SET UP** | Enable garage button for the connected Waveshare; Android notification permission; stored BLE address matching |
| Gauge shows **GARAGE SIGNAL SENT**, but no switch/door change | Signal only means Android posted a notification. Check Samsung **Settings → Modes and Routines** notification IF/THEN action, notification keyword, and history |
| Samsung routine says **Ran** but virtual switch stays OFF | Check detailed **THEN → SmartThings** action result, selected device/location, and error history. "Ran" alone isn't proof of device control |
| Samsung routine ran, virtual switch already ON | SmartThings needs a **new OFF → ON edge**. Ensure the prior garage routine resets `Garage Trigger` to OFF; don't leave it latched ON |
| Virtual switch turns ON, but garage doesn't move | **First verify `Garage Door Opener` currently reports the correct door state.** The actual failure during setup was an incorrect garage state, not a BLE or notification failure. Check IF = **all** and virtual switch = ON |
| Door opens once but next press does nothing | Inspect virtual switch: it must have reset to OFF. Check both THEN action lists |
| Virtual switch resets to OFF but door doesn't change | Garage command may have been attempted without succeeding. Review SmartThings history, Meross state, device connectivity, and physical door; don't infer success from the reset |
| Garage moves unexpectedly | Disable garage button in Companion and disable relevant routines; inspect all IF conditions, actions and reported states before re-enabling |
| Android navigation works only while app remains open | Currently BLE lives in `MainActivity` and `onDestroy()` closes it. Persistent background BLE service is separate future work; do not assume unattended operation |

## 9. Source / builds / handoff

- ESP32 firmware: [JohnathanKong/gc8-gauge-display](https://github.com/JohnathanKong/gc8-gauge-display). In a **VS Code ESP-IDF terminal**: `git pull`, then `.\build.bat`. Flash only the **development** Waveshare after a successful build; do **not** erase/hold BOOT just for ordinary troubleshooting.
- Android: [JohnathanKong/gc8-gauge-display-android](https://github.com/JohnathanKong/gc8-gauge-display-android). In its Windows PowerShell repository folder: `git pull` then `docker compose run --rm android gradle --no-daemon assembleDebug`, then install `app/build/outputs/apk/debug/app-debug.apk`.
- Firmware/Android log indicators: `gc8_ble`, `gc8_garage_button`, `GARAGE_BUTTON`, `GARAGE_ROUTINE`, `GARAGE_SUBSCRIBE`. Android **Save drive diagnostics to file** provides debug evidence.
- The physical button's successful app notification does **not** prove the garage moved. Neither Android nor ESP32 authenticates with Meross or tracks actual door state.
- **Security limitation:** A stored BLE address is **not cryptographic pairing or identity verification**. This is a convenient but presently not security-hardened path to a physical garage-door actuator. For long-term use, add authenticated BLE pairing/authorization and threat modelling; the garage should retain its normal safety protections.
- **No further firmware or Android source changes are necessary for the currently reported working routines.** The two automation systems and SmartThings virtual switch are configured through their respective UIs.

### Session verification history

1. Earlier BLE advertising failure (NimBLE GATT registration error rc=3) prevented Android discovery; firmware callback fix pushed in [ea90d27](https://github.com/JohnathanKong/gc8-gauge-display/commit/ea90d27e38da9395c748ae26c07dc35afd4d8592).
2. Owner subsequently installed the updated APK/firmware, connected BLE, granted GC8 Companion notifications, enabled the garage button, and saw Waveshare confirmation after PWR.
3. The missing bridge was identified: Samsung **Modes and Routines** notification IF → SmartThings virtual switch ON. Owner configured it with the keyword `Garage Trigger`.
4. Pressing again initially didn't move the garage because the virtual switch was already ON; owner configured the SmartThings routines with **all conditions** and added **Turn off Garage Trigger** after door actions.
5. Owner provided the **Garage Open** configuration screenshot. Initial manual switch tests still failed because **the reported garage-door state was incorrect**. Owner corrected the door state and then reported **"now it works properly."**
6. No screenshots or logs of every final door transition were supplied here. Do not claim independently verified safety, automatic background operation, or reliable detection of physical door arrival.
