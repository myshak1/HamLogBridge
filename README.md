# HamLog Bridge

Aplikacja Android, która nasłuchuje datagramów UDP w formacie **WSJT-X Message Protocol**
(to samo, czego słuchają GridTracker, WsjtxWatcher, Log4OM czy JTAlert), zapisuje z nich
QSO i rozsyła je do zewnętrznych logów online.

Zaprojektowana pod zmodyfikowane Xiegu X6100 z FT8 i wysyłką UDP przez WiFi, ale działa
z dowolnym nadajnikiem tego protokołu — także z WSJT-X/JTDX na PC.

---

## Co robi

```
   X6100 (FT8, WiFi)
        │  UDP :2237   magic 0xADBCCBDA
        ▼
   ┌─────────────────────────────────────────┐
   │  BridgeService (foreground)             │
   │  · WifiLock + MulticastLock + WakeLock  │
   │  · dekoder QDataStream                  │
   └───────┬───────────────────┬─────────────┘
           │                   │
           │ raw relay         │ QSO Logged (5) / Logged ADIF (12)
           ▼                   ▼
   GridTracker,           Room DB + plik ADIF
   Log4OM, N1MM      ┌────────┴──────────────────────────┐
   na PC             ▼                                   ▼
                 WorkManager (retry z backoffem)   lokalny .adi
                     │
     ┌───────────────┼───────────────┬──────────┬─────────┐
     ▼               ▼               ▼          ▼         ▼
  Cloudlog/       QRZ.com        Club Log   HRDLog     eQSL     + webhook
  Wavelog         Logbook        realtime   .net       .cc
```

## Obsługiwane typy komunikatów

| Typ | Nazwa | Zastosowanie |
|-----|-------|--------------|
| 0 | Heartbeat | wykrycie źródła |
| 1 | Status | częstotliwość, mod, DE/DX call, stan TX |
| 2 | Decode | lista dekodów na ekranie Monitor |
| 3 | Clear | czyszczenie listy dekodów |
| 5 | QSO Logged | **główne źródło logu** — budowany jest z niego rekord ADIF |
| 6 | Close | zamknięcie sesji |
| 10 | WSPR Decode | parsowane, na razie nieużywane |
| 12 | Logged ADIF | gotowy rekord ADIF; jeśli dotyczy tego samego QSO co typ 5, **uzupełnia** istniejący wpis zamiast go duplikować |

Dekoder jest tolerancyjny: pola w protokole były zawsze tylko dopisywane na końcu, więc
uproszczona implementacja (np. firmware, który wypełnia tylko 2–3 pierwsze pola `Status`)
jest obsłużona bez wyjątku. Pakiety spoza protokołu są ignorowane i odnotowane w Event trace.

## Deduplikacja

Klucz `CALL | YYYYMMDDHHMM (UTC) | pasmo`. WSJT-X wysyła to samo QSO dwa razy
(typ 5 i typ 12) — drugi komunikat wzbogaca istniejący rekord zamiast tworzyć nowy.
Kolumna w bazie ma indeks UNIQUE, więc ponowne wysłanie tego samego pakietu nic nie psuje.

## Integracje

| Logger | Endpoint | Wymagane dane |
|--------|----------|---------------|
| Cloudlog / Wavelog | `POST {base}/index.php/api/qso` | URL, klucz API (RW), id profilu stacji |
| QRZ.com Logbook | `POST logbook.qrz.com/api` (`ACTION=INSERT`) | klucz API logbooka |
| Club Log | `POST clublog.org/realtime.php` | e-mail, hasło, znak, klucz API |
| HRDLog.net | `POST robot.hrdlog.net/NewEntry.aspx` | znak + kod uploadu |
| eQSL.cc | `POST eqsl.cc/qslcard/importADIF.cfm` | user, hasło, opcjonalnie nickname QTH |
| Webhook | dowolny URL, ADIF albo JSON, własne nagłówki | URL |
| Plik ADIF | `Android/data/pl.hamlogbridge/files/Documents/HamLogBridge/` | — (zawsze włączony) |

Rozróżniane są błędy **przejściowe** (brak sieci, 5xx, 429 → ponawianie z wykładniczym
backoffem, do 12 prób) i **trwałe** (zły klucz, 401/403, odrzucony rekord → status FAILED
i przycisk „Retry" po poprawieniu ustawień). Duplikaty po stronie serwera traktowane są
jako sukces.

**LoTW nie jest obsługiwany** — wymaga podpisania rekordu kluczem prywatnym z certyfikatu
TQSL, czego nie da się bezpiecznie zrobić na telefonie. Praktyczne obejście: eksport pliku
ADIF i podpisanie na PC, albo synchronizacja LoTW po stronie Cloudloga.

## Konfiguracja radia

1. Telefon i radio w tej samej sieci WiFi (albo radio podłączone do hotspotu telefonu —
   wtedy IP telefonu to zwykle `192.168.43.1`).
2. W firmware X6100 ustaw adres docelowy UDP na **IP telefonu**, port **2237**.
   Broadcast (`192.168.x.255`) też zadziała — aplikacja binduje się na `0.0.0.0`.
3. W aplikacji: zakładka **Setup** → wpisz znak i lokator, włącz wybrane loggery,
   potem **Monitor** → *Start listening*.

Jeśli firmware wysyła multicast (np. `224.0.0.1`), wpisz grupę w polu *Multicast group* —
wtedy dodatkowo dołączany jest MulticastLock.

## Praca w tle

Usługa działa jako foreground service z powiadomieniem i trzyma `WIFI_MODE_FULL_LOW_LATENCY`,
MulticastLock oraz partial WakeLock. Mimo to **wyłącz optymalizację baterii** dla aplikacji
(Ustawienia → Aplikacje → HamLog Bridge → Bateria → Bez ograniczeń), inaczej producenci
tacy jak Xiaomi/Samsung i tak potrafią uśpić socket.

## Relay

Pole *Relay datagrams to* przekazuje każdy odebrany pakiet bajt w bajt dalej, np.
`192.168.1.10:2237, 192.168.1.20:2333`. Dzięki temu telefon może być „mostem" a GridTracker
albo Log4OM na PC dalej dostaje ten sam strumień.

## Budowanie

```bash
# Android Studio Ladybug+ / AGP 8.6, JDK 17, Kotlin 2.0.21, minSdk 26
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Testy jednostkowe dekodera: `./gradlew test`

## Status weryfikacji

Warstwa protokołu (`wsjtx/`) i generator ADIF (`adif/`) zostały skompilowane i przetestowane
na wygenerowanych, prawdziwych ładunkach QDataStream — przechodzą komplet asercji
(offsety pól, konwersja Julian day → UTC, długości pól ADIF liczone w bajtach, tolerancja
skróconych pakietów). Warstwy zależne od Androida (UI, service, Room, uploadery) nie zostały
skompilowane w tym środowisku — brak Android SDK — więc pierwszy `./gradlew assembleDebug`
może wymagać drobnych poprawek wersji zależności.

## Struktura

```
app/src/main/java/pl/hamlogbridge/
├── wsjtx/     QDataReader, WsjtxCodec, WsjtxMessage   ← protokół
├── net/       UdpListener, UdpRelay
├── adif/      Adif                                    ← budowa/parsowanie rekordów
├── data/      Room (QsoEntity, UploadEntity), Settings, Repository
├── upload/    LogTarget + 6 implementacji, UploadWorker, LocalAdifWriter
├── service/   BridgeService, BootReceiver
└── ui/        Monitor, Log, Setup (Compose)
```

## Uwagi bezpieczeństwa

Hasła i klucze API trzymane są w DataStore w prywatnym katalogu aplikacji, bez szyfrowania.
Jeśli to ma znaczenie w Twoim scenariuszu, warto podmienić `SettingsStore` na
EncryptedSharedPreferences albo Android Keystore.
