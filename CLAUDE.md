# CLAUDE.md — HamLog Bridge project context

This file is read automatically at the start of every Claude Code session.
It holds design decisions that **are not visible from the code alone** and are easy
to break with a "fix". Read it before changing anything in `wsjtx/`, `adif/`
or `data/Repository.kt`.

---

## What this is

A bridge between a radio transmitting FT8 and online logs. The Android app listens
for UDP in the **WSJT-X Message Protocol** format, extracts QSOs from it and forwards
them to external loggers. Target source: a modified **Xiegu X6100** with FT8
and UDP output over WiFi. Also works with WSJT-X/JTDX on a PC.

The functional reference point is **WsjtxWatcher**, not GridTracker.
Locator maps, DXCC tracking and alerts are deliberately absent — see "Out of scope".

Package: `pl.hamlogbridge` · minSdk 26 · Kotlin 2.0.21 · Compose · AGP 8.6.1 · JDK 17

---

## Architecture

```
UdpListener (0.0.0.0:2237)
    │
    ├─► UdpRelay ─────────────► PC running GridTracker (byte for byte, unchanged)
    │
    └─► WsjtxCodec.decode()
            │
            └─► Repository.handle(msg)
                    ├─ Decode/Status  → StateFlow → UI (never stored in the DB)
                    └─ QsoLogged (5) / LoggedAdif (12)
                            ├─► .adi file (always, immediately)
                            ├─► Room: QsoEntity + N × UploadEntity(PENDING)
                            └─► WorkManager → UploadWorker → HTTP
```

Layers:

| Directory | Responsibility | Depends on Android? |
|---|---|---|
| `wsjtx/` | protocol decoder, pure Kotlin | no |
| `adif/` | ADIF building and parsing, pure Kotlin | no |
| `net/` | UDP sockets | minimally |
| `data/` | Room, DataStore, `Repository` — ties everything together | yes |
| `upload/` | `LogTarget` + implementations, `UploadWorker` | yes |
| `service/` | foreground service, locks | yes |
| `ui/` | Compose, 3 screens + `BridgeViewModel` | yes |

`wsjtx/` and `adif/` have no `android.*` imports and **must stay that way** — this is
what lets them be tested on a plain JVM without an emulator. Do not pull `Context` in there.

---

## Decisions not to "fix"

### 1. The decoder is deliberately tolerant

In `WsjtxCodec.decode()` every field is read with the pattern `if (r.hasMore) r.xxx() else default`.
It looks like defensive noise — it is not. Fields in this protocol have **only ever been
appended at the end** of the structure, and alternative firmware (including the X6100) often
fills in only the first 2–3 `Status` fields. Rigidly reading all 22 fields crashes the parser
on real traffic from the radio.

Do not replace this with "proper" sequential reading. Add new fields **at the end**
of the list, never in the middle.

### 2. Deduplication key

`CALL | YYYYMMDDHHMM (UTC) | band` — the `dedupKey` field in `QsoEntity`, UNIQUE index.

Reason: WSJT-X sends **the same QSO twice** — as `QSO Logged` (type 5) and as
`Logged ADIF` (type 12). Without this, every contact would land in the log twice.
`Repository.storeFromAdif()` deliberately **updates** the existing row instead of inserting
a new one, because the type 12 record is richer.

Minute resolution is intentional — the radio and phone clocks can drift apart
by a few seconds. Do not go down to seconds.

### 3. ADIF field lengths counted in bytes

`Adif.field()` uses `toByteArray(UTF_8).size`, not `String.length`.
`<NAME:7>Michał` — seven bytes, six characters. Switching to `.length` corrupts records
with non-ASCII characters in a way that loggers only reject on the server side.

### 4. Retry / Fatal distinction

`UploadResult` has three variants, and this is the essence of how the queue works:

- `Retry` — no network, 5xx, 429. Stays `PENDING`, WorkManager retries with backoff, max 12 attempts.
- `Fatal` — bad key, 401/403, rejected record. Goes to `FAILED`, **stops retrying**.
  The user fixes the settings and taps "Retry".
- `Ok` — including a **server-side duplicate**. The QSO is in the log, the goal is achieved.

Do not collapse this into a boolean. Endlessly retrying a bad API key is a straight path
to getting the account banned on QRZ or Club Log.

### 5. Types 5 and 12 are the only log sources

`Decode` (type 2) and `Status` (type 1) **never** create a QSO — they only end up in a `StateFlow`
on the Monitor screen. A decode is not a contact.

### 6. Locks in BridgeService

`WIFI_MODE_FULL_LOW_LATENCY` + `MulticastLock` + partial `WakeLock`. All three are
required: without the MulticastLock some devices do not even receive broadcast, not just multicast.
Do not remove any of them "because a foreground service is enough" — it is not.

### 7. cleartextTrafficPermitted=true

In `network_security_config.xml`, deliberately. People run Cloudlog/Wavelog on
`http://192.168.x.x` in their LAN. Public services use TLS anyway.

---

## Out of scope (deliberately)

- **Locator map, DXCC, worked/confirmed, alerts** — that is the heart of GridTracker, a separate project.
  Instead there is a UDP relay: the phone logs, the packets go on to the PC.
- **LoTW** — requires signing with the private key from a TQSL certificate. This cannot be done
  sensibly and securely on a phone. Workaround: export ADIF and sign on a PC, or
  sync on the Cloudlog side.
- **Transmitting** (types 4, 8, 9 — Reply, Halt Tx, Free Text). The app only listens.
  If it is ever added, a QDataStream encoder has to be written — currently there is only a reader.

---

## Verification status

**Actually tested.** `wsjtx/` and `adif/` were compiled and run through a set of assertions
on generated, real QDataStream payloads: field offsets, Julian day → UTC conversion,
ADIF lengths in bytes, truncated packets, foreign datagrams, band boundaries.
The equivalent lives in `app/src/test/java/pl/hamlogbridge/WsjtxCodecTest.kt` — run `./gradlew test`.

**Unverified.** Everything else was written without a compiler — there was no Android SDK in
the environment where it was created. Expect minor errors in `ui/` (imports, Compose signatures),
and possibly `compose-bom` / KSP version mismatches. **Do not assume the code compiles.**

The uploaders have not been tested against live endpoints — the request shapes come from the API
documentation, not from real traffic. On the first real QSO, check `lastMessage` in `UploadEntity`.

---

## Conventions

- **Everything in English**: code comments, UI text, README, this file and all other docs.
- A code comment explains **why**, not what. If a line is obvious, do not comment it.
- Colors only from `ui/theme/Theme.kt`. Zero hardcoded `Color(0xFF...)` in screens.
- Callsign, frequency and the decode line — always `Mono`/`MonoSmall`. Columns carry meaning.
- Everything network and database related: `suspend`, `Dispatchers.IO`. `Repository` does not know Compose.
- New logger = new object implementing `LogTarget` + an entry in `Targets.all`. The rest (settings UI,
  queue, retry) hooks up automatically. Do not add special cases in `UploadWorker`.

## Security

Passwords and API keys are stored in DataStore in the app's private directory, **unencrypted**.
If this needs to be raised, replace `SettingsStore` with EncryptedSharedPreferences or Keystore.
Do not log the values of fields marked `secret = true` to `Repository.note()` or to Logcat.

## Building

```bash
./gradlew test            # decoder + ADIF, no emulator
./gradlew assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
```

Without Android Studio you need a `local.properties` with `sdk.dir` — there is a template in `local.properties.example`.

## Live diagnostics

The **Monitor** screen has an "Event trace" section: every rejected packet with its source address and the reason.
This is the first place to look when the radio is transmitting but nothing shows up.
Typical causes: wrong port, the radio sends to a different address, Android put the socket to sleep
(check that battery optimization is disabled).
