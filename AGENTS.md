# AGENTS.md — instrukcje dla agentów roboczych

Ten plik jest czytany przez agenta pomagającego przy pracy w tym repozytorium. Zawiera najważniejsze zasady i ograniczenia projektu, których nie da się bezpiecznie odtworzyć z samego kodu.

## Czym jest ten projekt

HamLog Bridge to most między radiem nadającym FT8 a logami online. Aplikacja Android nasłuchuje UDP w formacie WSJT-X Message Protocol, wyciąga z niego QSO i wysyła je do zewnętrznych loggerów.

Docelowe źródło to zmodyfikowane Xiegu X6100 z FT8 i wysyłką UDP przez WiFi. Działa też z WSJT-X/JTDX na PC.

Punkt odniesienia funkcjonalny to WsjtxWatcher, nie GridTracker. Mapy lokatorów, śledzenie DXCC ani alerty są poza zakresem.

Pakiet: `pl.hamlogbridge` · minSdk 26 · Kotlin 2.0.21 · Compose · AGP 8.6.1 · JDK 17.

## Architektura

- `wsjtx/` — dekoder protokołu WSJT-X, czysty Kotlin, bez Androida.
- `adif/` — budowa i parsowanie ADIF, czysty Kotlin, bez Androida.
- `net/` — sockety UDP.
- `data/` — Room, DataStore, Repository, łączenie warstw.
- `upload/` — `LogTarget`, implementacje loggerów, `UploadWorker`.
- `service/` — foreground service, locki.
- `ui/` — Compose, 3 ekrany + `BridgeViewModel`.

Najważniejsza ścieżka przepływu:

- `UdpListener` odbiera datagramy UDP.
- `UdpRelay` wysyła pakiety do GridTrackera bez modyfikacji.
- `WsjtxCodec.decode()` dekoduje komunikaty.
- `Repository.handle(msg)` zapisuje dane do stanu UI oraz do logu.
- `QsoLogged` / `LoggedAdif` tworzą wpis w `.adi`, `Room` i kolejce uploadu.
- `WorkManager` wysyła dane przez HTTP do zewnętrznych logerów.

## Kluczowe decyzje projektowe

### 1. Dekoder ma być tolerancyjny

W `WsjtxCodec.decode()` każde pole jest odczytywane wzorcem `if (r.hasMore) r.xxx() else default`.
To nie jest przypadkowy bałagan. Pole w protokole były zawsze dopisywane na końcu. Firmware alternatywne (w tym X6100) często wypełniają tylko pierwsze 2–3 pola `Status` i błędne sztywne odczytywanie wszystkich pól zniszczyłoby parser na prawdziwym ruchu.

Nie zamieniaj tego na „porządnie” sekwencyjne czytanie. Nowe pola dopisuj na końcu listy, nigdy w środku.

### 2. Klucz deduplikacji

`CALL | YYYYMMDDHHMM (UTC) | pasmo` to `dedupKey` w `QsoEntity`, z indeksem UNIQUE.

Powód: WSJT-X wysyła to samo QSO dwa razy — jako `QSO Logged` (typ 5) i `Logged ADIF` (typ 12). Bez tego kontakt pojawiałby się podwójnie. `Repository.storeFromAdif()` celowo aktualizuje istniejący rekord zamiast dodawać nowy, bo wpis z typu 12 jest bogatszy.

Rozdzielczość minutowa jest zamierzona; zegary radia i telefonu mogą się rozjechać o kilka sekund.

### 3. Długości pól ADIF liczone są w bajtach

`Adif.field()` używa `toByteArray(UTF_8).size`, nie `String.length`.
`<NAME:7>Michał` ma 7 bajtów, choć 6 znaków. Zmiana na `.length` psuje rekordy z polskimi znakami i może zostać odrzucona przez serwer.

### 4. Retry vs Fatal

`UploadResult` ma trzy warianty i to jest istota działania kolejki:

- `Retry` — brak sieci, 5xx, 429. Zostaje `PENDING`, `WorkManager` ponawia z backoffem, maksymalnie 12 prób.
- `Fatal` — zły klucz, 401/403, odrzucony rekord. Idzie do `FAILED` i przestaje próbować. Użytkownik poprawia ustawienia i klika „Retry”.
- `Ok` — w tym także duplikat po stronie serwera. QSO jest już w logu, cel osiągnięty.

Nie zwijaj tego do boolean. Ponawianie w nieskończoność złego klucza API to prosta droga do zbanowania konta.

### 5. Typ 5 i 12 są jedynymi źródłami logu

`Decode` (typ 2) i `Status` (typ 1) nigdy nie tworzą QSO; trafiają wyłącznie do `StateFlow` na ekranie Monitor. Dekod to nie kontakt.

### 6. Locki w `BridgeService`

`WIFI_MODE_FULL_LOW_LATENCY` + `MulticastLock` + partial `WakeLock` są wszystkie potrzebne. Bez `MulticastLock` część urządzeń nie odbiera nawet broadcastu, nie tylko multicastu. Nie usuwaj żadnego „bo foreground service wystarczy” — nie wystarcza.

### 7. `cleartextTrafficPermitted=true`

W `network_security_config.xml` celowo. Cloudlog/Wavelog często stoi na `http://192.168.x.x` w LAN-ie. Serwisy publiczne idą po TLS.

## Zakres i granice

Nie dodawaj nowych funkcji z poza zakresu projektu:

- brak map lokatorów, DXCC, worked/confirmed, alertów,
- brak LoTW, bo wymaga podpisu kluczem prywatnym i certyfikatu TQSL,
- brak nadawania (typy 4, 8, 9) — aplikacja tylko słucha.

## Testy i komendy

Zawsze korzystaj z tych komend, jeśli zmieniasz logikę dekodera lub ADIF:

```bash
./gradlew test
./gradlew assembleDebug
```

## Bezpieczeństwo

- Hasła i klucze API trzymane są w `DataStore` w prywatnym katalogu aplikacji, bez szyfrowania.
- Nie loguj wartości pól oznaczonych `secret = true` do `Repository.note()` ani do Logcata.
- W przyszłości rozważyć `EncryptedSharedPreferences` lub `Keystore`.

## Konwencje pracy

- Komentarze i teksty w UI po angielsku; README i dokumentacja po polsku.
- Komentarze w kodzie wyjaśniają `dlaczego`, nie `co`.
- Kolory tylko z `ui/theme/Theme.kt`. Brak hardcoded `Color(0xFF...)` na ekranach.
- Znak wywoławczy, częstotliwość i linia dekodu są zawsze `Mono` / `MonoSmall`.
- Wszystko sieciowe i bazodanowe: `suspend`, `Dispatchers.IO`. `Repository` nie zna Compose.
- Nowy logger to nowy obiekt implementujący `LogTarget` + wpis w `Targets.all`.

## Ważne ograniczenia

- `wsjtx/` i `adif/` nie mają importów `android.*` i tak ma zostać.
- Nie dodawaj zależności od `Context` do tych pakietów.
- Nie zakładaj, że reszta projektu kompiluje się bez Android SDK; cały kod poza `wsjtx/` i `adif/` może wymagać drobnych korekt w środowisku kompilacji.
- Użytkownik jest z Polski; dokumentacja i komentarze w repozytorium mają być w języku polskim tam, gdzie to dotyczy repozytorium, a nie tylko w UI.

## Przydatne punkty wejścia

- `WsjtxCodec` — dekoder protokołu
- `Repository` — główny orchestrator logiki biznesowej
- `UploadWorker` — kolejka i retry
- `BridgeService` — usługa systemowa i locki
- `Monitor` — ekran diagnostyczny

Zawsze preferuj minimalne, root-cause fix zamiast „naprawiania” ogólnych symptomów.
