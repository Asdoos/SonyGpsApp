# Sony GPS Link

[![Release](https://img.shields.io/github/v/release/Asdoos/SonyGpsApp?style=flat-square)](https://github.com/Asdoos/SonyGpsApp/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/Asdoos/SonyGpsApp/release.yml?style=flat-square&label=build)](https://github.com/Asdoos/SonyGpsApp/actions)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-MIT-blue?style=flat-square)](LICENSE)

Android app that transfers GPS coordinates from a smartphone to Sony cameras via Bluetooth Low Energy (BLE) — without the original Sony Creators App.

> Reverse-engineered from **Sony Creators App v3.3.1** (XAPK, decompiled with jadx 1.5.5).

**[⬇ Download latest APK](https://github.com/Asdoos/SonyGpsApp/releases/latest)**

---

## Feature Overview

| Feature | Detail |
|---|---|
| BLE camera discovery | Scan for Sony devices (manufacturer ID 301) |
| GPS transfer | WGS-84, every 5 seconds, 91 or 95-byte packet |
| APO keepalive | Prevents camera sleep mode, every 9 seconds |
| Auto-reconnect | Up to 10 attempts after unexpected disconnection |
| Foreground Service | GPS + BLE run persistently, even with the app closed |

---

## Supported Cameras

All Sony cameras that support the proprietary Sony BLE GPS protocol (`LocationInfoFromSmartPhone_1_0` / `_1_1`), including:

- Sony ZV-E1, ZV-E10, ZV-1 II
- Sony ILCE series (α7 IV, α7C, α7R V, α6700, ...)
- Sony FX3, FX30

The camera must be **paired via Bluetooth** through the Android system settings before using this app.

---

## How It Works

### 1. Camera Discovery (BLE Scan)

Sony cameras always include **manufacturer ID 301** (`0x012D` = Sony Corporation, per Bluetooth SIG) in their BLE advertisement. The app filters exclusively for this ID:

```
ScanFilter: ManufacturerData(companyId = 301, data = [])
ScanMode:   SCAN_MODE_LOW_LATENCY
```

Discovered devices are presented to the user as a list showing name, MAC address, and signal strength (RSSI).

---

### 2. BLE GATT Profile (Sony-proprietary)

Sony cameras expose a proprietary GATT profile with two relevant services:

#### Service 1 — Camera Control (`CC00`)
```
UUID: 8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF
```

| Characteristic (Prefix) | Type | Content |
|---|---|---|
| `0000CC02` | WRITE | Generic camera control commands (incl. APO avoidance) |

#### Service 2 — GPS / Location (`DD00`)
```
UUID: 8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF
```

| Characteristic (Prefix) | Type | Content |
|---|---|---|
| `0000DD01` | NOTIFY | Camera → phone: transfer disabled signal `{3,1,2,0}` |
| `0000DD11` | WRITE  | GPS payload (91 or 95 bytes) |
| `0000DD21` | READ   | Capability flags: byte[4] & `0x02` → timezone support |
| `0000DD30` | WRITE  | Lock: `{0x01}` = acquire, `{0x00}` = release |
| `0000DD31` | WRITE  | Location transfer: `{0x01}` = on, `{0x00}` = off |
| `0000DD32` | READ   | Time correction setting (informational) |
| `0000DD33` | READ   | Area adjustment setting (informational) |

> **Lookup method:** Characteristics are not resolved by their full 128-bit UUID but by **prefix-matching** the first 8 characters of the UUID string — exactly as in the original Sony app: `uuid.toString().uppercase().startsWith(prefix)`.

---

### 3. Connection Protocol (Handshake Sequence)

After GATT connect and service discovery, the following sequence is executed:

```
① enableNotify(DD01)         — camera can abort transfer at any time
      ↓ CCCD descriptor written
② write {0x01} → DD30       — acquire exclusive lock
      ↓ write callback OK
③ write {0x01} → DD31       — enable GPS transfer on camera
      ↓ write callback OK
④ read DD32                  — read time correction setting
      ↓ read callback
⑤ read DD33                  — read area adjustment setting
      ↓ read callback
⑥ read DD21                  — read capability flags
      ↓ read callback: byte[4] & 0x02 → timezoneSupport = true/false
⑦ → onReady(): start GPS updates + APO keepalive timer
```

All GATT operations run **serialised** through an `ArrayDeque`-based op-queue, since BLE only permits one concurrent operation. Each operation is only started once the callback of the previous one has been received.

---

### 4. GPS Packet Format

Source: `BluetoothLeUtil.setLocationAndTime()` + `TransferringLocationInfoWithLockState.onLocationUpdated()`

GPS coordinates are written as a **big-endian binary packet** to characteristic `DD11`. Two formats exist depending on the camera's capabilities:

#### Format A — 91 bytes (no timezone support)
#### Format B — 95 bytes (with timezone support, when `DD21[4] & 0x02 != 0`)

```
Offset  Size  Value / Description
────────────────────────────────────────────────────────────
[0]      1     0x00         (fixed)
[1]      1     89 / 93      (payload length minus 2; Format A / B)
[2]      1     0x08         (fixed)
[3]      1     0x02         (fixed)
[4]      1     0xFC (= -4)  (fixed)
[5]      1     0x00 / 0x03  (Format A / B)
[6]      1     0x00         (fixed)
[7]      1     0x00         (fixed)
[8]      1     0x10         (fixed)
[9]      1     0x10         (fixed)
[10]     1     0x10         (fixed)
────────── Payload ────────────────────────────────────────
[11–14]  4     Latitude  × 10,000,000  as big-endian Int32
                Example: 48.137154° → 481,371,540 = 0x1CB77914
[15–18]  4     Longitude × 10,000,000  as big-endian Int32
                Example: 11.575533° → 115,755,330 = 0x06E6A382
[19–20]  2     UTC year  as big-endian Int16 (e.g. 2025 = 0x07E9)
[21]     1     UTC month (1-based, 1–12)
[22]     1     UTC day   (1–31)
[23]     1     UTC hour
[24]     1     UTC minute
[25]     1     UTC second
[26–90]  65    Reserved / padding (zeros)
────────── Format B only ──────────────────────────────────
[91–92]  2     Timezone offset in minutes, big-endian Int16
                = TimeZone.getDefault().rawOffset / 60,000
                Example: UTC+1 → 60 = 0x003C
[93–94]  2     DST savings in minutes, big-endian Int16
                = getDSTSavings() / 60,000 if daylight saving is active, else 0
────────────────────────────────────────────────────────────
```

**Coordinate encoding:** `(double) degrees × 1e7` → `toInt()` → `ByteBuffer.allocate(4).putInt(...)`  
**Timestamp:** UTC timezone, derived from `location.getTime()` via `Calendar.getInstance(UTC)`  
**Validation:** GPS fixes older than **10 seconds** are discarded (based on `elapsedRealtimeNanos` delta)

---

### 5. APO Keepalive (Auto Power Off Avoidance)

The camera disconnects BLE after ~30 seconds of inactivity (standby / sleep mode).

The original Sony app therefore sends a keepalive command every **9 seconds**:

```
Service:        8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF
Characteristic: 0000CC02-...
Value:          {0x03, 0x08, 0x10, 0x00}
Interval:       9,000 ms
```

Source: `ExecutingApoAvoidanceState.onGattCharacteristicWrite()` → `startCommandTimeout(apoAvoidanceRunnable, 9000L)`

The keepalive runs **independently** of GPS updates through the same op-queue and is rescheduled after every successful write callback. A failed write does not abort the session — the next attempt is made at the regular interval, since isolated failures are non-critical.

---

### 6. Auto-Reconnect

Upon unexpected connection loss (camera sleep despite keepalive, out of range, etc.) the app automatically attempts to reconnect:

- **Delay:** 4 seconds between attempts
- **Maximum attempts:** 10
- **Counter reset:** on successfully completed handshake (`onReady`)
- **No reconnect** if the user manually tapped "Stop" (`userStopped` flag)

---

### 7. Foreground Service & Energy Efficiency

#### Why a Foreground Service?

| Android mechanism | Effect without service | With Foreground Service |
|---|---|---|
| Doze Light (~3 min screen off) | GPS callbacks batched / delayed | Exempt |
| Doze Deep (extended idle) | `Handler.postDelayed` freezes → APO keepalive dies | Exempt |
| Memory pressure | Process is killed | `START_STICKY`: system restarts automatically |

#### Service type declaration (mandatory on Android 14+ / API 34)
```xml
android:foregroundServiceType="location|connectedDevice"
```
- `location` — grants GPS access from a background foreground service
- `connectedDevice` — grants BLE access from a background foreground service

#### Power consumption estimate (screen off)

| Component | Avg. current |
|---|---|
| GPS chip (`HIGH_ACCURACY`, 5 s interval) | ~40–60 mA |
| BLE (connected + ~6 writes/10 s) | ~2–4 mA |
| CPU (callbacks, packet assembly) | ~1–2 mA |
| APO keepalive (included in BLE) | ~0.1 mA |
| **App total (incremental)** | **~45–65 mA** |
| **Realistic incl. system baseline** | **~75–90 mA** |

→ **~4–7% battery per hour** on a typical 4,000 mAh device  
→ Comparable to a GPS tracking app like Strava running in the background

---

### 8. Architecture

```
┌─────────────────────────────────┐
│           MainActivity          │
│  ┌──────────────┐               │
│  │  BLE Scan    │  (short-lived)│
│  └──────┬───────┘               │
│         │ device selected       │
│  ┌──────▼───────────────────┐   │
│  │  startForegroundService  │   │
│  │  bindService (Binder)    │   │
│  └──────────────────────────┘   │
│  StatusListener (UI updates)    │
└────────────┬────────────────────┘
             │ Binder (LocalBinder)
┌────────────▼────────────────────┐
│      GpsForegroundService       │  ← runs persistently in background
│                                 │
│  FusedLocationProviderClient    │  GPS every 5 s
│  Handler (APO timer, reconnect) │
│                                 │
│  ┌──────────────────────────┐   │
│  │      SonyCameraGatt      │   │
│  │  ┌──────────────────┐    │   │
│  │  │  ArrayDeque      │    │   │  Serialised op-queue
│  │  │  (Op-Queue)      │    │   │
│  │  └──────────────────┘    │   │
│  │  GATT Callback           │   │
│  └──────────────────────────┘   │
│                                 │
│  Persistent Notification        │  "Stop" action directly in notification
└─────────────────────────────────┘
             │ BLE GATT
┌────────────▼────────────────────┐
│          Sony Camera            │
│  Service CC00 (Control)         │  ← APO keepalive {3,8,16,0} every 9 s
│  Service DD00 (GPS)             │  ← GPS packet (91/95 B) every 5 s
└─────────────────────────────────┘
```

---

## Project Structure

```
SonyGpsApp/
├── app/src/main/
│   ├── java/com/example/sonygps/
│   │   ├── GpsForegroundService.kt   Foreground service: GPS + BLE session management
│   │   ├── MainActivity.kt           UI: BLE scan, camera selection, service binding
│   │   ├── SonyCameraGatt.kt         BLE GATT client: handshake, op-queue, APO keepalive
│   │   └── SonyGpsPacket.kt          GPS packet assembly (91/95 bytes, Sony format)
│   ├── res/
│   │   ├── layout/activity_main.xml
│   │   ├── drawable/ic_launcher_*.xml
│   │   ├── mipmap-anydpi-v26/
│   │   └── values/themes.xml, colors.xml
│   └── AndroidManifest.xml
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties            android.useAndroidX=true
└── gradlew / gradlew.bat
```

---

## Build & Installation

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9.x
- Physical device running Android 8.0+ (API 26) with BLE support

### Steps
1. Open the `SonyGpsApp/` folder in Android Studio
2. Wait for Gradle sync to complete (all dependencies are downloaded automatically)
3. Connect a device via USB
4. **Run** (`Shift+F10`)

> **Note:** An emulator supports neither BLE nor real GPS — a physical device is required for testing.

---

## Permissions

| Permission | Purpose | Required from |
|---|---|---|
| `ACCESS_FINE_LOCATION` | GPS coordinates | API 1 |
| `ACCESS_COARSE_LOCATION` | Fallback location | API 1 |
| `BLUETOOTH_SCAN` | BLE scanning | API 31 |
| `BLUETOOTH_CONNECT` | GATT connection | API 31 |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` | BLE (legacy) | API ≤ 30 |
| `FOREGROUND_SERVICE` | Start a foreground service | API 28 |
| `FOREGROUND_SERVICE_LOCATION` | GPS access in foreground service | API 34 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | BLE access in foreground service | API 34 |
| `POST_NOTIFICATIONS` | Display service notification | API 33 |

---

## Known Limitations

- **Pairing required:** The camera must be paired via the Android system Bluetooth settings (not through this app). The pairing protocol from the Sony app is not implemented.
- **BLE GPS protocol only:** WiFi (PTP/IP, port 15740) and USB (PTP/MTP) are not supported — BLE only.
- **No live viewfinder:** The app transfers GPS data only. Camera control and image transfer are not implemented.
- **GPS accuracy:** `PRIORITY_HIGH_ACCURACY` uses the hardware GPS chip. Indoors or in poor reception conditions, fixes older than 10 seconds are automatically discarded.

---

## Sources & Reverse Engineering

| File (decompiled from Sony Creators App v3.3.1) | Finding |
|---|---|
| `BluetoothLeUtil.java` | GPS packet encoding (`setLocationAndTime`) |
| `TransferringLocationInfoWithLockState.java` | Handshake sequence, packet structure, byte layout |
| `ExecutingApoAvoidanceState.java` | APO keepalive command `{3,8,16,0}`, interval 9,000 ms |
| `BluetoothGattUtil.java` | UUID constants, byte constants for all commands |
| `EnumCameraInfo.java` | Timezone support flag (Format A vs. B) |
| `BluetoothLeUtil.startLeScanWithLowPower()` | BLE scan filter (manufacturer ID 301) |
