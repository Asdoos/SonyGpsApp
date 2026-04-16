# Sony GPS Link

Android-App, die GPS-Koordinaten vom Smartphone via Bluetooth Low Energy (BLE) an Sony-Kameras überträgt — ohne die originale Sony Creators App.

Reverse-engineered aus **Sony Creators App v3.3.1** (XAPK, dekompiliert mit jadx 1.5.5).

---

## Funktionsübersicht

| Funktion | Detail |
|---|---|
| BLE-Kamera-Erkennung | Scan nach Sony-Geräten (Hersteller-ID 301) |
| GPS-Übertragung | WGS-84, alle 5 Sekunden, 91 oder 95 Byte Paket |
| APO-Keepalive | Verhindert Kamera-Schlafmodus, alle 9 Sekunden |
| Auto-Reconnect | Bis zu 10 Versuche nach unerwartetem Verbindungsverlust |
| Foreground Service | GPS + BLE laufen dauerhaft, auch bei geschlossener App |

---

## Unterstützte Kameras

Alle Sony-Kameras die das propriäre Sony-BLE-GPS-Protokoll (`LocationInfoFromSmartPhone_1_0` / `_1_1`) unterstützen, u.a.:

- Sony ZV-E1, ZV-E10, ZV-1 II
- Sony ILCE-Serie (α7 IV, α7C, α7R V, α6700, ...)
- Sony FX3, FX30

Die Kamera muss vorher über die Systemeinstellungen des Smartphones **per Bluetooth gekoppelt** (gepairt) sein.

---

## Technische Funktionsweise

### 1. Kamera-Erkennung (BLE Scan)

Sony-Kameras senden im BLE-Advertisement immer die **Hersteller-ID 301** (`0x012D` = Sony Corporation laut Bluetooth SIG). Die App filtert ausschließlich nach dieser ID:

```
ScanFilter: ManufacturerData(companyId = 301, data = [])
ScanMode:   SCAN_MODE_LOW_LATENCY
```

Gefundene Geräte werden dem Nutzer als Liste mit Name, MAC-Adresse und Signalstärke (RSSI) angezeigt.

---

### 2. BLE GATT-Profil (Sony-proprietär)

Sony-Kameras exponieren ein proprietäres GATT-Profil mit zwei relevanten Services:

#### Service 1 — Kamera-Control (`CC00`)
```
UUID: 8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF
```

| Characteristic (Prefix) | Typ | Inhalt |
|---|---|---|
| `0000CC02` | WRITE | Generische Kamera-Steuerbefehle (inkl. APO-Avoidance) |

#### Service 2 — GPS / Location (`DD00`)
```
UUID: 8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF
```

| Characteristic (Prefix) | Typ | Inhalt |
|---|---|---|
| `0000DD01` | NOTIFY | Kamera → Handy: Transfer wurde deaktiviert `{3,1,2,0}` |
| `0000DD11` | WRITE  | GPS-Nutzlast (91 oder 95 Byte) |
| `0000DD21` | READ   | Capability-Flags: Byte[4] & `0x02` → Timezone-Support |
| `0000DD30` | WRITE  | Lock: `{0x01}` = belegen, `{0x00}` = freigeben |
| `0000DD31` | WRITE  | Location-Transfer: `{0x01}` = ein, `{0x00}` = aus |
| `0000DD32` | READ   | Zeitkorrektur-Einstellung (informativ) |
| `0000DD33` | READ   | Gebietskorrektur-Einstellung (informativ) |

> **Lookup-Methode:** Characteristics werden nicht anhand der vollständigen 128-Bit-UUID gesucht, sondern per **Prefix-Match** der ersten 8 Zeichen des UUID-Strings (exakt wie in der originalen Sony-App: `uuid.toString().uppercase().startsWith(prefix)`).

---

### 3. Verbindungsprotokoll (Handshake-Sequenz)

Nach dem GATT-Connect und Service-Discovery wird folgende Sequenz durchgeführt:

```
① enableNotify(DD01)         — Kamera kann Transfer jederzeit abbrechen
      ↓ CCCD-Descriptor geschrieben
② write {0x01} → DD30       — Exklusiven Lock belegen
      ↓ Write-Callback OK
③ write {0x01} → DD31       — GPS-Transfer auf Kamera aktivieren
      ↓ Write-Callback OK
④ read DD32                  — Zeitkorrektur-Einstellung lesen
      ↓ Read-Callback
⑤ read DD33                  — Gebietskorrektur-Einstellung lesen
      ↓ Read-Callback
⑥ read DD21                  — Capability-Flags lesen
      ↓ Read-Callback: Byte[4] & 0x02 → timezoneSupport = true/false
⑦ → onReady(): GPS-Updates + APO-Keepalive starten
```

Alle GATT-Operationen laufen **serialisiert** durch eine `ArrayDeque`-basierte Op-Queue, da BLE nur eine gleichzeitige Operation erlaubt. Jede Operation wird erst gestartet, wenn der Callback der vorherigen eingegangen ist.

---

### 4. GPS-Paketformat

Quelle: `BluetoothLeUtil.setLocationAndTime()` + `TransferringLocationInfoWithLockState.onLocationUpdated()`

GPS-Koordinaten werden als **Big-Endian-Binärpaket** an Characteristic `DD11` geschrieben. Es gibt zwei Formate abhängig von den Kamera-Capabilities:

#### Format A — 91 Byte (kein Timezone-Support)
#### Format B — 95 Byte (mit Timezone-Support, wenn `DD21[4] & 0x02 != 0`)

```
Offset  Größe  Wert / Beschreibung
────────────────────────────────────────────────────────────
[0]      1     0x00         (fest)
[1]      1     89 / 93      (Payload-Länge minus 2; Format A / B)
[2]      1     0x08         (fest)
[3]      1     0x02         (fest)
[4]      1     0xFC (= -4)  (fest)
[5]      1     0x00 / 0x03  (Format A / B)
[6]      1     0x00         (fest)
[7]      1     0x00         (fest)
[8]      1     0x10         (fest)
[9]      1     0x10         (fest)
[10]     1     0x10         (fest)
────────── Nutzdaten ──────────────────────────────────────
[11–14]  4     Latitude  × 10 000 000  als Big-Endian Int32
                Beispiel: 48.137154° → 481 371 540 = 0x1CB77914
[15–18]  4     Longitude × 10 000 000  als Big-Endian Int32
                Beispiel: 11.575533° → 115 755 330 = 0x06E6A382
[19–20]  2     UTC-Jahr  als Big-Endian Int16 (z.B. 2025 = 0x07E9)
[21]     1     UTC-Monat (1-basiert, 1–12)
[22]     1     UTC-Tag   (1–31)
[23]     1     UTC-Stunde
[24]     1     UTC-Minute
[25]     1     UTC-Sekunde
[26–90]  65    Reserviert / Padding (Nullen)
────────── Nur Format B ───────────────────────────────────
[91–92]  2     Timezone-Offset in Minuten, Big-Endian Int16
                = TimeZone.getDefault().rawOffset / 60 000
                Beispiel: UTC+1 → 60 = 0x003C
[93–94]  2     DST-Ersparnis in Minuten, Big-Endian Int16
                = getDSTSavings() / 60 000 wenn Sommerzeit aktiv, sonst 0
────────────────────────────────────────────────────────────
```

**Koordinaten-Kodierung:** `(double) degrees × 1e7` → `toInt()` → `ByteBuffer.allocate(4).putInt(...)`  
**Zeitstempel:** UTC-Zeitzone, aus `location.getTime()` via `Calendar.getInstance(UTC)`  
**Validierung:** GPS-Fix wird verworfen wenn älter als **10 Sekunden** (`elapsedRealtimeNanos`-Differenz)

---

### 5. APO-Keepalive (Auto Power Off Avoidance)

Die Kamera trennt BLE nach ~30 Sekunden ohne Aktivität (Standby/Schlafmodus).

Die originale Sony-App schickt deshalb alle **9 Sekunden** einen Keepalive-Befehl:

```
Service:        8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF
Characteristic: 0000CC02-...
Wert:           {0x03, 0x08, 0x10, 0x00}
Intervall:      9 000 ms
```

Quelle: `ExecutingApoAvoidanceState.onGattCharacteristicWrite()` → `startCommandTimeout(apoAvoidanceRunnable, 9000L)`

Der Keepalive läuft **unabhängig** von GPS-Updates über dieselbe Op-Queue und wird nach jedem erfolgreichen Write-Callback neu geplant. Bei gescheitertem Write wird der nächste Versuch nach dem regulären Intervall unternommen (kein Abbruch, da einzelne Fehler unkritisch sind).

---

### 6. Auto-Reconnect

Bei unerwartetem Verbindungsverlust (Kamera-Schlafmodus trotz Keepalive, Reichweite, etc.) versucht die App automatisch wieder zu verbinden:

- **Verzögerung:** 4 Sekunden zwischen Versuchen  
- **Maximale Versuche:** 10  
- **Zähler-Reset:** bei erfolgreich abgeschlossenem Handshake (`onReady`)  
- **Kein Reconnect** wenn der Nutzer manuell auf „Stoppen" gedrückt hat (`userStopped`-Flag)

---

### 7. Foreground Service & Energieeffizienz

#### Warum Foreground Service?

| Android-Mechanismus | Auswirkung ohne Service | Mit Foreground Service |
|---|---|---|
| Doze Light (~3 min Screen off) | GPS-Callbacks gebündelt/verzögert | Exempt |
| Doze Deep (längeres Idle) | `Handler.postDelayed` friert ein → APO-Keepalive stirbt | Exempt |
| Speichermangel | Prozess wird gekillt | `START_STICKY`: System startet neu |

#### Service-Typ-Deklaration (Android 14+ / API 34 Pflicht)
```xml
android:foregroundServiceType="location|connectedDevice"
```
- `location` — Erlaubt GPS-Zugriff im Hintergrund
- `connectedDevice` — Erlaubt BLE-Kommunikation im Hintergrund

#### Energieverbrauch (Abschätzung, Screen off)

| Komponente | ∅ Strom |
|---|---|
| GPS-Chip (`HIGH_ACCURACY`, 5 s Intervall) | ~40–60 mA |
| BLE (connected + ~6 Writes/10 s) | ~2–4 mA |
| CPU (Callbacks, Paket-Assemblierung) | ~1–2 mA |
| APO-Keepalive (anteilig in BLE) | ~0.1 mA |
| **Gesamt (App, inkrementell)** | **~45–65 mA** |
| **Realistisch inkl. System** | **~75–90 mA** |

→ **~4–7 % Akku pro Stunde** auf einem typischen 4 000-mAh-Gerät  
→ Vergleichbar mit einer GPS-Tracking-App wie Strava im Hintergrund

---

### 8. Architektur

```
┌─────────────────────────────────┐
│           MainActivity          │
│  ┌──────────────┐               │
│  │  BLE Scan    │  (kurzlebig)  │
│  └──────┬───────┘               │
│         │ Gerät ausgewählt      │
│  ┌──────▼───────────────────┐   │
│  │  startForegroundService  │   │
│  │  bindService (Binder)    │   │
│  └──────────────────────────┘   │
│  StatusListener (UI-Updates)    │
└────────────┬────────────────────┘
             │ Binder (LocalBinder)
┌────────────▼────────────────────┐
│      GpsForegroundService       │  ← läuft dauerhaft im Hintergrund
│                                 │
│  FusedLocationProviderClient    │  GPS alle 5 s
│  Handler (APO-Timer, Reconnect) │
│                                 │
│  ┌──────────────────────────┐   │
│  │      SonyCameraGatt      │   │
│  │  ┌──────────────────┐    │   │
│  │  │  ArrayDeque      │    │   │  Serialisierte Op-Queue
│  │  │  (Op-Queue)      │    │   │
│  │  └──────────────────┘    │   │
│  │  GATT Callback           │   │
│  └──────────────────────────┘   │
│                                 │
│  Persistent Notification        │  "Stoppen"-Aktion direkt in Notif.
└─────────────────────────────────┘
             │ BLE GATT
┌────────────▼────────────────────┐
│         Sony Kamera             │
│  Service CC00 (Control)         │  ← APO-Keepalive {3,8,16,0} alle 9s
│  Service DD00 (GPS)             │  ← GPS-Paket (91/95 B) alle 5s
└─────────────────────────────────┘
```

---

## Projektstruktur

```
SonyGpsApp/
├── app/src/main/
│   ├── java/com/example/sonygps/
│   │   ├── GpsForegroundService.kt   Foreground Service: GPS + BLE-Session-Management
│   │   ├── MainActivity.kt           UI: BLE-Scan, Kamera-Auswahl, Service-Binding
│   │   ├── SonyCameraGatt.kt         BLE GATT Client: Handshake, Op-Queue, APO-Keepalive
│   │   └── SonyGpsPacket.kt          GPS-Paket-Assemblierung (91/95 Byte, Sony-Format)
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

### Voraussetzungen
- Android Studio Hedgehog (2023.1.1) oder neuer
- Android SDK 34
- Kotlin 1.9.x
- Gerät mit Android 8.0+ (API 26), BLE-Unterstützung

### Schritte
1. Ordner `SonyGpsApp/` in Android Studio öffnen
2. Gradle Sync abwarten (lädt automatisch alle Abhängigkeiten)
3. Gerät per USB verbinden oder Emulator starten
4. **Run** (`Shift+F10`)

> **Hinweis:** Ein Emulator kann weder BLE noch echtes GPS — für Tests wird ein physisches Gerät benötigt.

---

## Berechtigungen

| Permission | Zweck | Pflicht ab |
|---|---|---|
| `ACCESS_FINE_LOCATION` | GPS-Koordinaten | API 1 |
| `ACCESS_COARSE_LOCATION` | Fallback-Standort | API 1 |
| `BLUETOOTH_SCAN` | BLE-Scan | API 31 |
| `BLUETOOTH_CONNECT` | GATT-Verbindung | API 31 |
| `BLUETOOTH` + `BLUETOOTH_ADMIN` | BLE (Legacy) | API ≤ 30 |
| `FOREGROUND_SERVICE` | Foreground Service starten | API 28 |
| `FOREGROUND_SERVICE_LOCATION` | GPS in Foreground Service | API 34 |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | BLE in Foreground Service | API 34 |
| `POST_NOTIFICATIONS` | Service-Notification anzeigen | API 33 |

---

## Bekannte Einschränkungen

- **Kopplung erforderlich:** Die Kamera muss über die Android-Systemeinstellungen (nicht über diese App) per Bluetooth Classic gekoppelt sein. Das Pairing-Protokoll selbst (aus der Sony-App) ist nicht implementiert.
- **Nur BLE-GPS-Protokoll:** WiFi (PTP/IP, Port 15740) und USB (PTP/MTP) werden nicht unterstützt — ausschließlich BLE.
- **Kein Live-Viewfinder:** Die App überträgt nur GPS-Daten, keine Kamera-Steuerung oder Bildübertragung.
- **GPS-Genauigkeit:** `PRIORITY_HIGH_ACCURACY` nutzt den Hardware-GPS-Chip. In Gebäuden oder bei schlechtem Empfang werden Fixes älter als 10 Sekunden automatisch verworfen.

---

## Quellen & Reverse Engineering

| Datei (dekompiliert aus Sony Creators App v3.3.1) | Erkenntnisse |
|---|---|
| `BluetoothLeUtil.java` | GPS-Paket-Kodierung (`setLocationAndTime`) |
| `TransferringLocationInfoWithLockState.java` | Handshake-Sequenz, Paket-Aufbau, Byte-Layout |
| `ExecutingApoAvoidanceState.java` | APO-Keepalive-Befehl `{3,8,16,0}`, Intervall 9 000 ms |
| `BluetoothGattUtil.java` | UUID-Konstanten, Byte-Konstanten aller Befehle |
| `EnumCameraInfo.java` | Timezone-Support-Flag (Format A vs. B) |
| `BluetoothLeUtil.startLeScanWithLowPower()` | BLE-Scan-Filter (Hersteller-ID 301) |
