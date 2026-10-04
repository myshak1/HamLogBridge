# HamLog Bridge

An Android app that listens for UDP datagrams in the **WSJT-X Message Protocol** format
(the same stream GridTracker, WsjtxWatcher, Log4OM and JTAlert consume), records QSOs
from it and forwards them to external online logs.

Designed for a modified Xiegu X6100 with FT8 and UDP output over WiFi, but it works
with any sender of this protocol, including WSJT-X/JTDX on a PC.

---

## What it does

```
   X6100 (FT8, WiFi)
        │  UDP :2237   magic 0xADBCCBDA
        ▼
   ┌─────────────────────────────────────────┐
   │  BridgeService (foreground)             │
   │  · WifiLock + MulticastLock + WakeLock  │
   │  · QDataStream decoder                  │
   └───────┬───────────────────┬─────────────┘
           │                   │
           │ raw relay         │ QSO Logged (5) / Logged ADIF (12)
           ▼                   ▼
   GridTracker,           Room DB + ADIF file
   Log4OM, N1MM      ┌────────┴──────────────────────────┐
   on a PC           ▼                                   ▼
                 WorkManager (retry with backoff)   local .adi
                     │
     ┌───────────────┼───────────────┬──────────┬─────────┐
     ▼               ▼               ▼          ▼         ▼
  Cloudlog/       QRZ.com        Club Log   HRDLog     eQSL     + webhook
  Wavelog         Logbook        realtime   .net       .cc
```

## Supported message types

| Type | Name | Used for |
|------|------|----------|
| 0 | Heartbeat | source detection |
| 1 | Status | frequency, mode, DE/DX call, TX state |
| 2 | Decode | decode list on the Monitor screen |
| 3 | Clear | clearing the decode list |
| 5 | QSO Logged | **main log source**, the ADIF record is built from it |
| 6 | Close | end of session |
| 10 | WSPR Decode | parsed, currently unused |
| 12 | Logged ADIF | ready-made ADIF record; if it refers to the same QSO as type 5, it **enriches** the existing entry instead of duplicating it |

The decoder is tolerant: fields in the protocol have only ever been appended at the end,
so simplified implementations (e.g. firmware that fills in only the first 2–3 `Status`
fields) are handled without exceptions. Non-protocol packets are ignored and recorded in
the Event trace.

## Deduplication

Key: `CALL | YYYYMMDDHHMM (UTC) | band`. WSJT-X sends the same QSO twice (type 5 and
type 12), so the second message enriches the existing record instead of creating a new one.
The database column has a UNIQUE index, so receiving the same packet again breaks nothing.

## Integrations

| Logger | Endpoint | Required data |
|--------|----------|---------------|
| Cloudlog / Wavelog | `POST {base}/index.php/api/qso` | URL, API key (RW), station profile id |
| QRZ.com Logbook | `POST logbook.qrz.com/api` (`ACTION=INSERT`) | logbook API key |
| Club Log | `POST clublog.org/realtime.php` | e-mail, password, callsign, API key |
| HRDLog.net | `POST robot.hrdlog.net/NewEntry.aspx` | callsign + upload code |
| eQSL.cc | `POST eqsl.cc/qslcard/importADIF.cfm` | user, password, optional QTH nickname |
| Webhook | any URL, ADIF or JSON, custom headers | URL |
| ADIF file | `Android/data/pl.hamlogbridge/files/Documents/HamLogBridge/` | none (always on) |

Errors are split into **transient** (no network, 5xx, 429 → retried with exponential
backoff, up to 12 attempts) and **permanent** (bad key, 401/403, rejected record → FAILED
status and a "Retry" button once the settings are fixed). Server-side duplicates count
as success.

**LoTW is not supported.** It requires signing each record with the private key from a
TQSL certificate, which cannot be done safely on a phone. Practical workaround: export
the ADIF file and sign it on a PC, or let Cloudlog sync with LoTW.

## Radio setup

1. Put the phone and the radio on the same WiFi network (or connect the radio to the
   phone's hotspot; the phone's IP is then usually `192.168.43.1`).
2. In the X6100 firmware, set the UDP destination to the **phone's IP**, port **2237**.
   Broadcast (`192.168.x.255`) also works, since the app binds to `0.0.0.0`.
3. In the app: **Setup** tab → enter your callsign and locator, enable the loggers you want,
   then **Monitor** → *Start listening*.

If the firmware sends multicast (e.g. `224.0.0.1`), enter the group in the *Multicast group*
field. The app then also joins it under a MulticastLock.

## Running in the background

The service runs as a foreground service with a notification and holds
`WIFI_MODE_FULL_LOW_LATENCY`, a MulticastLock and a partial WakeLock. Even so, **disable
battery optimization** for the app (Settings → Apps → HamLog Bridge → Battery → Unrestricted);
otherwise vendors such as Xiaomi or Samsung may still put the socket to sleep.

## Relay

The *Relay datagrams to* field forwards every received packet byte for byte, e.g.
`192.168.1.10:2237, 192.168.1.20:2333`. This lets the phone act as a bridge while
GridTracker or Log4OM on a PC keeps receiving the same stream.

## Building

```bash
# Android Studio Ladybug+ / AGP 8.6, JDK 17, Kotlin 2.0.21, minSdk 26
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Decoder unit tests: `./gradlew test`

## Verification status

The protocol layer (`wsjtx/`) and the ADIF generator (`adif/`) have been compiled and tested
against generated, real QDataStream payloads and pass the full set of assertions
(field offsets, Julian day → UTC conversion, ADIF field lengths counted in bytes, tolerance
of truncated packets). The Android-dependent layers (UI, service, Room, uploaders) were not
compiled in the environment where they were written (no Android SDK), so the first
`./gradlew assembleDebug` may need minor dependency-version fixes.

## Structure

```
app/src/main/java/pl/hamlogbridge/
├── wsjtx/     QDataReader, WsjtxCodec, WsjtxMessage   ← protocol
├── net/       UdpListener, UdpRelay
├── adif/      Adif                                    ← building/parsing records
├── data/      Room (QsoEntity, UploadEntity), Settings, Repository
├── upload/    LogTarget + 6 implementations, UploadWorker, LocalAdifWriter
├── service/   BridgeService, BootReceiver
└── ui/        Monitor, Log, Setup (Compose)
```

## Security notes

Passwords and API keys are stored in DataStore in the app's private directory, unencrypted.
If that matters for your use case, consider replacing `SettingsStore` with
EncryptedSharedPreferences or the Android Keystore.
