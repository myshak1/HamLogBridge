# AGENTS.md — instructions for coding agents

This file is read by agents helping out in this repository. It contains the key rules and constraints of the project that cannot be safely reconstructed from the code alone.

## What this project is

HamLog Bridge is a bridge between a radio transmitting FT8 and online logs. The Android app listens for UDP in the WSJT-X Message Protocol format, extracts QSOs from it and sends them to external loggers.

The target source is a modified Xiegu X6100 with FT8 and UDP output over WiFi. It also works with WSJT-X/JTDX on a PC.

The functional reference point is WsjtxWatcher, not GridTracker. Locator maps, DXCC tracking and alerts are out of scope.

Package: `pl.hamlogbridge` · minSdk 26 · Kotlin 2.0.21 · Compose · AGP 8.6.1 · JDK 17.

## Architecture

- `wsjtx/` — WSJT-X protocol decoder, pure Kotlin, no Android.
- `adif/` — ADIF building and parsing, pure Kotlin, no Android.
- `net/` — UDP sockets.
- `data/` — Room, DataStore, Repository, wiring the layers together.
- `upload/` — `LogTarget`, logger implementations, `UploadWorker`.
- `service/` — foreground service, locks.
- `ui/` — Compose, 3 screens + `BridgeViewModel`.

Main data flow:

- `UdpListener` receives UDP datagrams.
- `UdpRelay` forwards packets to GridTracker unmodified.
- `WsjtxCodec.decode()` decodes messages.
- `Repository.handle(msg)` writes data to UI state and to the log.
- `QsoLogged` / `LoggedAdif` create an entry in the `.adi` file, `Room` and the upload queue.
- `WorkManager` sends data over HTTP to external loggers.

## Key design decisions

### 1. The decoder must be tolerant

In `WsjtxCodec.decode()` every field is read with the pattern `if (r.hasMore) r.xxx() else default`.
This is not accidental clutter. Fields in the protocol have always been appended at the end. Alternative firmware (including the X6100) often fills in only the first 2–3 `Status` fields, and rigidly reading every field would break the parser on real traffic.

Do not replace this with "proper" sequential reading. Add new fields at the end of the list, never in the middle.

### 2. Deduplication key

`CALL | YYYYMMDDHHMM (UTC) | band` is `dedupKey` in `QsoEntity`, with a UNIQUE index.

Reason: WSJT-X sends the same QSO twice — as `QSO Logged` (type 5) and `Logged ADIF` (type 12). Without this, every contact would appear twice. `Repository.storeFromAdif()` deliberately updates the existing record instead of inserting a new one, because the type 12 record is richer.

Minute resolution is intentional; the radio and phone clocks can drift apart by a few seconds.

### 3. ADIF field lengths are counted in bytes

`Adif.field()` uses `toByteArray(UTF_8).size`, not `String.length`.
`<NAME:7>Michał` is 7 bytes but 6 characters. Switching to `.length` breaks records with non-ASCII characters, and the server may reject them.

### 4. Retry vs Fatal

`UploadResult` has three variants, and they are the core of how the queue works:

- `Retry` — no network, 5xx, 429. Stays `PENDING`; `WorkManager` retries with backoff, up to 12 attempts.
- `Fatal` — bad key, 401/403, rejected record. Goes to `FAILED` and stops retrying. The user fixes the settings and taps "Retry".
- `Ok` — including a server-side duplicate. The QSO is already in the log, so the goal is achieved.

Do not collapse this into a boolean. Endlessly retrying a bad API key is an easy way to get the account banned.

### 5. Types 5 and 12 are the only log sources

`Decode` (type 2) and `Status` (type 1) never create a QSO; they only go to a `StateFlow` on the Monitor screen. A decode is not a contact.

### 6. Locks in `BridgeService`

`WIFI_MODE_FULL_LOW_LATENCY` + `MulticastLock` + partial `WakeLock` are all required. Without `MulticastLock`, some devices do not even receive broadcast, not just multicast. Do not remove any of them "because a foreground service is enough" — it is not.

### 7. `cleartextTrafficPermitted=true`

Deliberately set in `network_security_config.xml`. Cloudlog/Wavelog often runs on `http://192.168.x.x` in the LAN. Public services use TLS anyway.

## Scope and boundaries

Do not add features outside the project's scope:

- no locator maps, DXCC, worked/confirmed or alerts,
- no LoTW, because it requires signing with a private key and a TQSL certificate,
- no transmitting (types 4, 8, 9) — the app only listens.

## Tests and commands

Always run these commands when you change decoder or ADIF logic:

```bash
./gradlew test
./gradlew assembleDebug
```

## Security

- Passwords and API keys are stored in `DataStore` in the app's private directory, unencrypted.
- Do not log the values of fields marked `secret = true` to `Repository.note()` or to Logcat.
- Consider `EncryptedSharedPreferences` or `Keystore` in the future.

## Working conventions

- Everything in English: code comments, UI text, README and all documentation.
- Code comments explain *why*, not *what*.
- Colors only from `ui/theme/Theme.kt`. No hardcoded `Color(0xFF...)` in screens.
- Callsign, frequency and the decode line are always `Mono` / `MonoSmall`.
- Everything network and database related: `suspend`, `Dispatchers.IO`. `Repository` does not know about Compose.
- A new logger is a new object implementing `LogTarget` + an entry in `Targets.all`.

## Important constraints

- `wsjtx/` and `adif/` have no `android.*` imports, and that must stay so.
- Do not add a `Context` dependency to these packages.
- Do not assume the rest of the project compiles without the Android SDK; all code outside `wsjtx/` and `adif/` may need minor fixes in a real build environment.

## Useful entry points

- `WsjtxCodec` — protocol decoder
- `Repository` — main orchestrator of business logic
- `UploadWorker` — queue and retry
- `BridgeService` — system service and locks
- `Monitor` — diagnostic screen

Always prefer a minimal, root-cause fix over "fixing" general symptoms.
