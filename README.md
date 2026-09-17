# Voice Camera Shutter

Powiedz **„smile”** przy otwartym aparacie — telefon zrobi zdjęcie.

Aparat Honora (MagicOS) nie ma wyzwalania głosem i nie da się tego włączyć w ustawieniach,
w przeciwieństwie do Samsunga. Ta aplikacja tę funkcję dokłada — i robi to tak, żeby zdjęcie
powstało w **natywnej aplikacji aparatu**, z całym przetwarzaniem Honora (HDR, tryb nocny,
portret). Własny aparat oparty o CameraX byłby prostszy, ale dawałby wyraźnie gorsze zdjęcia.

Mikrofon działa **tylko wtedy, gdy aparat jest na pierwszym planie** — nie przez całą dobę.

## Hasła wyzwalające

Domyślnie `smile`. Listę zmienisz w aplikacji — hasła oddzielone przecinkami, do 1000 znaków,
np. `smile, cheese, take a photo`. Dozwolone są litery, spacje i apostrofy; dopasowanie obejmuje
całe frazy, więc `smile` nie odpali się na `smiled`, a `take a photo` działa jako jedno hasło.
Zmiana listy restartuje sam silnik rozpoznawania — usługa i model zostają w pamięci.

## Jak to działa

```
Ty: „smile”
  ↓
VoiceShutterService  (foreground service, mikrofon)
  ↓  Vosk ze słownikiem ograniczonym do skonfigurowanych haseł + ["[unk]"]
ShutterAccessibilityService
  ↓  1. findAccessibilityNodeInfosByViewId(zapamiętane id)
  ↓  2. przeszukanie drzewa po content-description
  ↓  3. dispatchGesture — dotknięcie środka przycisku
Natywny aparat robi zdjęcie
```

Ta sama usługa dostępności pełni dwie role: wykrywa, że aparat wyszedł na pierwszy plan
(i wtedy uruchamia nasłuch), oraz naciska spust.

### Kiedy nasłuch się wyłącza

To okazało się najtrudniejszą częścią. MagicOS bez przerwy przykrywa aparat własnymi oknami
(`launcher`, `systemui`, pasek wyszukiwania) na ułamki sekundy. Jeśli potraktować to jako
„użytkownik wyszedł z aparatu", mikrofon gaśnie po kilku sekundach, mimo że aparat jest na
ekranie — objawia się to tym, że pierwsze 2-3 zdjęcia działają, a potem zapada cisza aż do
ponownego otwarcia aparatu.

Dlatego nasłuch nie kończy się na podstawie tego, co jest na wierzchu, tylko sprawdza,
**czy okno aparatu nadal istnieje na liście okien**. `CameraManager.AvailabilityCallback`
wygląda na właściwsze narzędzie, ale na tym telefonie raportuje kamerę jako wolną nawet
w trakcie robienia zdjęcia — nie nadaje się.

### Po co wyciszenie po zdjęciu

Przez sekundę po trafieniu strumień audio jest czytany, ale ignorowany. Bez tego końcówka
wypowiedzi (a na telefonach z niewyciszoną migawką także jej dźwięk) wraca do mikrofonu
i wyzwala serię 2-3 zdjęć. Wyciszenie działa na poziomie dźwięku, nie jako blokada
wyzwalania — blokada z długim oknem zjadała świadome, kolejne polecenia.

### Dlaczego nie „wyślij keypress”

Pilot Bluetooth do statywu wysyła zwykły `KEYCODE_VOLUME_UP`, więc naturalny pomysł jest taki,
żeby aplikacja wysłała ten sam keycode. **Nie da się.** Wstrzyknięcie zdarzenia klawiszowego do
cudzego okna wymaga uprawnienia `INJECT_EVENTS` o poziomie ochrony `signature` — dostają je
wyłącznie aplikacje podpisane kluczem platformy. `adb shell input keyevent 25` działa, bo `shell`
to osobny, systemowy uid. `adb shell pm grant` też tego nie nada — działa tylko dla uprawnień
runtime. Stąd usługa dostępności zamiast keypressa.

### Dlaczego Vosk, a nie Porcupine

Porcupine jest technicznie lepszym silnikiem wake-word, ale wymaga konta, klucza, uprawnienia
`INTERNET` i comiesięcznej walidacji licencji online. Vosk jest Apache 2.0 i w pełni offline.
**Aplikacja nie ma uprawnienia `INTERNET` w ogóle** — coś, co słucha mikrofonu i czyta drzewo UI
innych aplikacji, nie powinno móc niczego wysłać na zewnątrz.

Vosk to pełny silnik rozpoznawania mowy, ale ograniczenie jego gramatyki do skonfigurowanych haseł
zamienia go w praktyce w detektor wake-word — może zwrócić tylko jedno z nich albo „nieznane”.

Silnik siedzi za interfejsem [`WakeWordDetector`](app/src/main/java/dev/opielka/voiceshutter/WakeWordDetector.kt),
więc podmiana na inny dotyka jednego miejsca konstrukcji w `VoiceShutterService`.

## Opóźnienie migawki

Suwakiem w aplikacji: `natychmiast`, `0,5 s` (domyślne i zalecane), `1 s`, `2 s`, `3 s`.
Suwak jest osią czasu, nie rzędem równych kroków — odstęp `2`→`3` s jest sześć razy szerszy
niż `0`→`0,5` s, a przeciągnięcie przyciąga do najbliższej wartości.

Opóźnienie jest tu celowe, nie tolerowane: bez niego migawka wyzwala się w momencie, gdy
jeszcze wymawiasz hasło, i łapie otwarte usta. Jeśli zamkniesz aparat w trakcie odliczania,
zdjęcie zostaje anulowane.

## Budowanie

Wymagane: JDK 17 i Android SDK (platform 35, build-tools 35).

```bash
./tools/fetch-model.sh     # model Vosk (~68 MB), trzymany poza gitem
./gradlew assembleDebug
./gradlew installDebug     # z podłączonym telefonem
```

Bez `fetch-model.sh` aplikacja zbuduje się, ale nie rozpozna słowa.

## Konfiguracja telefonu

```bash
./tools/setup-device.sh
```

Skrypt nadaje uprawnienia, włącza usługę dostępności i wyjątek od optymalizacji baterii.
Na koniec sprawdza, czy usługa faktycznie wystartowała, i jeśli nie — wypisze, co kliknąć ręcznie.

**Wyjątek od optymalizacji baterii nie jest opcjonalny.** Android 12+ zabrania startowania
foreground service z tła, a aktywna usługa dostępności nie daje zwolnienia z tego zakazu.
Zwolnienie daje dopiero wyjątek bateryjny — bez niego nasłuch nie uruchomi się automatycznie.

### Krok, którego ADB nie załatwi (Honor/MagicOS)

Honor ma ponad standardowym Doze własną warstwę **PowerGenie**, z wewnętrzną białą listą bez
publicznego API. Trzeba raz kliknąć ręcznie:

> Ustawienia → Bateria → Uruchamianie aplikacji → Voice Shutter → **Zarządzaj ręcznie** →
> włączyć autostart, uruchamianie pośrednie i działanie w tle.

Bez tego „smile” działa po instalacji, a przestaje po kilku godzinach albo po restarcie.

## Połączenie bezprzewodowe

Telefon i Mac w tej samej sieci Wi-Fi. Parowanie raz:

> Ustawienia → Opcje programisty → Debugowanie bezprzewodowe → „Sparuj urządzenie kodem parowania"

```bash
./tools/adb-wireless.sh pair <IP:PORT> <6-cyfrowy-kod>
```

Potem w każdej kolejnej sesji wystarczy `./tools/adb-wireless.sh`. Parowanie przeżywa restart telefonu;
zmienia się natomiast port połączenia, dlatego skrypt wyszukuje go przez mDNS zamiast pytać o niego użytkownika.

## Diagnostyka

**Logcat na tym telefonie nie działa.** MagicOS po cichu zjada wyjście logcat — nie przechodzi nawet
`adb shell log -t TAG cokolwiek`. Dlatego aplikacja pisze własny dziennik do pliku:

```bash
adb shell run-as dev.opielka.voiceshutter cat files/voice-shutter.log
```

To działa tylko dla buildu debug (`run-as` wymaga `debuggable`). Dziennik jest ograniczony do 64 KB
i loguje zdarzenia aparatu, wyzwolenia, rozpoznane frazy (`Słyszę: "..."`) oraz co jakiś czas poziom
sygnału z mikrofonu — nie każdą zmianę okna, bo przy takim szumie bufor kasuje to, czego się szuka.

`Słyszę:` i poziom sygnału są tu nie bez powodu: bez nich awaria rozpoznawania jest nie do
odróżnienia od martwego mikrofonu i od zwykłego nietrafienia w hasło. Trzy różne przyczyny,
trzy różne naprawy.

Sprawdzenie samej ścieżki dostępności, bez udziału mikrofonu (build debug):

```bash
adb shell am broadcast -a dev.opielka.voiceshutter.TEST_SHUTTER
```

Jeśli to robi zdjęcie, a „smile” nie — problem jest w warstwie głosowej, nie w klikaniu spustu.

## Gdy aktualizacja aparatu zepsuje wyzwalanie

Identyfikator przycisku migawki nie jest zaszyty w kodzie — jest wykrywany i zapamiętywany
per pakiet aparatu. Jeśli aktualizacja go zmieni, aplikacja sama wróci do przeszukiwania drzewa.
Gdyby i to zawiodło, przy otwartym aparacie:

```bash
./tools/dump-camera-ui.sh
```

Skrypt zrzuca drzewo UI i wypisuje kandydatów na spust.

## Ograniczenia

- Sterowanie głosem **podczas nagrywania wideo** najpewniej nie zadziała — aplikacja aparatu
  przejmuje mikrofon na wyłączność.
- Praca z **zablokowanego ekranu** jest niepewna — usługa dostępności może nie widzieć okna
  nad lockscreenem.
- Hasła muszą być **angielskie** — dołączony model to `vosk-model-small-en-us`. Vosk przyjmie
  słowo, którego nie ma w swoim słowniku, i po prostu nigdy go nie rozpozna, bez żadnego
  ostrzeżenia, więc każde nowe hasło trzeba sprawdzić w praktyce.
