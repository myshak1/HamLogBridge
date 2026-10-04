# CLAUDE.md — kontekst projektu HamLog Bridge

Ten plik czytany jest automatycznie na starcie każdej sesji Claude Code.
Zawiera decyzje projektowe, których **nie widać z samego kodu** i które łatwo
zepsuć „poprawką". Przeczytaj go zanim zaczniesz zmieniać cokolwiek w `wsjtx/`,
`adif/` albo `data/Repository.kt`.

---

## Czym to jest

Most między radiem nadającym FT8 a logami online. Aplikacja Android nasłuchuje
UDP w formacie **WSJT-X Message Protocol**, wyciąga z niego QSO i rozsyła je do
zewnętrznych loggerów. Docelowe źródło: zmodyfikowane **Xiegu X6100** z FT8
i wysyłką UDP przez WiFi. Działa też z WSJT-X/JTDX na PC.

Punkt odniesienia funkcjonalny to **WsjtxWatcher**, nie GridTracker.
Mapy lokatorów, śledzenia DXCC ani alertów świadomie nie ma — patrz „Poza zakresem".

Pakiet: `pl.hamlogbridge` · minSdk 26 · Kotlin 2.0.21 · Compose · AGP 8.6.1 · JDK 17

---

## Architektura

```
UdpListener (0.0.0.0:2237)
    │
    ├─► UdpRelay ─────────────► PC z GridTrackerem (bajt w bajt, bez zmian)
    │
    └─► WsjtxCodec.decode()
            │
            └─► Repository.handle(msg)
                    ├─ Decode/Status  → StateFlow → UI (nie trafia do bazy)
                    └─ QsoLogged (5) / LoggedAdif (12)
                            ├─► plik .adi (zawsze, natychmiast)
                            ├─► Room: QsoEntity + N × UploadEntity(PENDING)
                            └─► WorkManager → UploadWorker → HTTP
```

Warstwy:

| Katalog | Odpowiedzialność | Zależy od Androida? |
|---|---|---|
| `wsjtx/` | dekoder protokołu, czysty Kotlin | nie |
| `adif/` | budowa i parsowanie ADIF, czysty Kotlin | nie |
| `net/` | sockety UDP | minimalnie |
| `data/` | Room, DataStore, `Repository` — spina wszystko | tak |
| `upload/` | `LogTarget` + implementacje, `UploadWorker` | tak |
| `service/` | foreground service, locki | tak |
| `ui/` | Compose, 3 ekrany + `BridgeViewModel` | tak |

`wsjtx/` i `adif/` nie mają importów `android.*` i **tak ma zostać** — dzięki temu
testują się w zwykłym JVM bez emulatora. Nie wciągaj tam `Context`.

---

## Decyzje, których nie „naprawiaj"

### 1. Dekoder jest celowo tolerancyjny

W `WsjtxCodec.decode()` każde pole czytane jest wzorcem `if (r.hasMore) r.xxx() else default`.
Wygląda to na defensywny szum — nie jest. Pola w tym protokole były **zawsze tylko
dopisywane na końcu** struktury, a alternatywne firmware (w tym X6100) wypełniają
często tylko 2–3 pierwsze pola `Status`. Sztywne czytanie 22 pól wywala parser
na prawdziwym ruchu z radia.

Nie zamieniaj tego na „porządne" sekwencyjne czytanie. Nowe pola dokładaj **na końcu**
listy, nigdy w środku.

### 2. Klucz deduplikacji

`CALL | YYYYMMDDHHMM (UTC) | pasmo` — pole `dedupKey` w `QsoEntity`, indeks UNIQUE.

Powód: WSJT-X wysyła **to samo QSO dwa razy** — jako `QSO Logged` (typ 5) i jako
`Logged ADIF` (typ 12). Bez tego każdy kontakt lądowałby w logu podwójnie.
`Repository.storeFromAdif()` celowo **aktualizuje** istniejący wiersz zamiast wstawiać nowy,
bo rekord z typu 12 jest bogatszy.

Rozdzielczość minutowa jest zamierzona — zegary radia i telefonu potrafią się rozjechać
o kilka sekund. Nie schodź do sekund.

### 3. Długości pól ADIF liczone w bajtach

`Adif.field()` używa `toByteArray(UTF_8).size`, nie `String.length`.
`<NAME:7>Michał` — siedem bajtów, sześć znaków. Zmiana na `.length` psuje rekordy
z polskimi znakami w sposób, który loggery odrzucą dopiero po stronie serwera.

### 4. Rozróżnienie Retry / Fatal

`UploadResult` ma trzy warianty i to jest istota działania kolejki:

- `Retry` — brak sieci, 5xx, 429. Zostaje `PENDING`, WorkManager ponawia z backoffem, max 12 prób.
- `Fatal` — zły klucz, 401/403, odrzucony rekord. Idzie na `FAILED`, **przestaje próbować**.
  Użytkownik poprawia ustawienia i klika „Retry".
- `Ok` — w tym **duplikat po stronie serwera**. QSO jest w logu, cel osiągnięty.

Nie zwijaj tego do boolean. Ponawianie w nieskończoność złego klucza API to prosta droga
do zbanowania konta na QRZ czy Club Logu.

### 5. Typ 5 i 12 to jedyne źródła logu

`Decode` (typ 2) i `Status` (typ 1) **nigdy** nie tworzą QSO — lądują tylko w `StateFlow`
na ekranie Monitor. Dekod to nie kontakt.

### 6. Locki w BridgeService

`WIFI_MODE_FULL_LOW_LATENCY` + `MulticastLock` + partial `WakeLock`. Wszystkie trzy są
potrzebne: bez MulticastLocka część urządzeń nie odbiera nawet broadcastu, nie tylko multicastu.
Nie usuwaj żadnego „bo foreground service wystarczy" — nie wystarcza.

### 7. cleartextTrafficPermitted=true

W `network_security_config.xml`, świadomie. Cloudlog/Wavelog stoi u ludzi na
`http://192.168.x.x` w LAN-ie. Serwisy publiczne i tak idą po TLS.

---

## Poza zakresem (świadomie)

- **Mapa lokatorów, DXCC, worked/confirmed, alerty** — to serce GridTrackera, osobny projekt.
  Zamiast tego jest relay UDP: telefon loguje, pakiety lecą dalej na PC.
- **LoTW** — wymaga podpisu kluczem prywatnym z certyfikatu TQSL. Nie da się tego zrobić
  sensownie i bezpiecznie na telefonie. Obejście: eksport ADIF i podpis na PC, albo
  synchronizacja po stronie Cloudloga.
- **Nadawanie** (typy 4, 8, 9 — Reply, Halt Tx, Free Text). Aplikacja tylko słucha.
  Jeśli kiedyś dojdzie, trzeba dopisać enkoder QDataStream — obecnie jest sam reader.

---

## Stan weryfikacji

**Przetestowane naprawdę.** `wsjtx/` i `adif/` skompilowane i przepuszczone przez zestaw
asercji na wygenerowanych, prawdziwych ładunkach QDataStream: offsety pól, konwersja
Julian day → UTC, długości ADIF w bajtach, pakiety skrócone, obce datagramy, granice pasm.
Odpowiednik jest w `app/src/test/java/pl/hamlogbridge/WsjtxCodecTest.kt` — uruchom `./gradlew test`.

**Niezweryfikowane.** Cała reszta pisana bez kompilatora — brak Android SDK w środowisku,
w którym powstała. Spodziewaj się drobnych błędów w `ui/` (importy, sygnatury Compose),
ewentualnie niezgodności wersji `compose-bom` / KSP. **Nie zakładaj, że kod się kompiluje.**

Uploaderów nie testowano na żywych endpointach — kształt żądań pochodzi z dokumentacji API,
nie z realnego ruchu. Przy pierwszym prawdziwym QSO patrz na `lastMessage` w `UploadEntity`.

---

## Konwencje

- Komentarze i teksty w UI **po angielsku**, README i ten plik po polsku (użytkownik jest z Polski).
- Komentarz w kodzie wyjaśnia **dlaczego**, nie co. Jeśli linia jest oczywista, nie komentuj.
- Kolory tylko z `ui/theme/Theme.kt`. Zero hardkodowanych `Color(0xFF...)` w ekranach.
- Znak wywoławczy, częstotliwość i linia dekodu — zawsze `Mono`/`MonoSmall`. Kolumny niosą znaczenie.
- Wszystko sieciowe i bazodanowe: `suspend`, `Dispatchers.IO`. `Repository` nie zna Compose.
- Nowy logger = nowy obiekt implementujący `LogTarget` + wpis w `Targets.all`. Reszta (UI ustawień,
  kolejka, retry) podłącza się sama. Nie dopisuj przypadków specjalnych w `UploadWorker`.

## Bezpieczeństwo

Hasła i klucze API leżą w DataStore w prywatnym katalogu aplikacji, **bez szyfrowania**.
Jeśli to ma być podniesione, podmień `SettingsStore` na EncryptedSharedPreferences albo Keystore.
Nie loguj wartości pól oznaczonych `secret = true` do `Repository.note()` ani do Logcata.

## Budowanie

```bash
./gradlew test            # dekoder + ADIF, bez emulatora
./gradlew assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
```

Bez Android Studio potrzebny `local.properties` z `sdk.dir` — jest szablon `local.properties.example`.

## Diagnostyka na żywo

Ekran **Monitor** ma sekcję „Event trace": każdy odrzucony pakiet z adresem źródłowym i powodem.
To pierwsze miejsce, gdzie należy patrzeć, gdy radio wysyła, a nic się nie pojawia.
Typowe przyczyny: zły port, radio wysyła na inny adres, Android uśpił socket
(sprawdź wyłączenie optymalizacji baterii).
