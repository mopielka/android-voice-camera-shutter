# Voice Camera Shutter

Powiedz **„smile”** przy otwartym aparacie — telefon zrobi zdjęcie.

Aparat Honora (MagicOS) nie ma wyzwalania głosem i nie da się tego włączyć w ustawieniach,
w przeciwieństwie do Samsunga. Ta aplikacja tę funkcję dokłada — i robi to tak, żeby zdjęcie
powstało w **natywnej aplikacji aparatu**, z całym przetwarzaniem Honora (HDR, tryb nocny,
portret). Własny aparat oparty o CameraX byłby prostszy, ale dawałby wyraźnie gorsze zdjęcia.

Mikrofon działa **tylko wtedy, gdy aparat jest na pierwszym planie** — nie przez całą dobę.

## Jak to działa

```
Ty: „smile”
  ↓
VoiceShutterService  (foreground service, mikrofon)
  ↓  Vosk ze słownikiem ograniczonym do ["smile", "[unk]"]
ShutterAccessibilityService
  ↓  1. findAccessibilityNodeInfosByViewId(zapamiętane id)
  ↓  2. przeszukanie drzewa po content-description
  ↓  3. dispatchGesture — dotknięcie środka przycisku
Natywny aparat robi zdjęcie
```

Ta sama usługa dostępności pełni dwie role: wykrywa, że aparat wyszedł na pierwszy plan
(i wtedy uruchamia nasłuch), oraz naciska spust.

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

Vosk to pełny silnik rozpoznawania mowy, ale ograniczenie jego słownika do `["smile", "[unk]"]`
zamienia go w praktyce w detektor wake-word — może zwrócić tylko „smile” albo „nieznane”.

Silnik siedzi za interfejsem [`WakeWordDetector`](app/src/main/java/dev/opielka/voiceshutter/WakeWordDetector.kt),
więc podmiana na inny dotyka jednego miejsca konstrukcji w `VoiceShutterService`.

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

## Diagnostyka

```bash
adb logcat -s VoiceShutter
```

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
- Słowo `„smile”` jest na sztywno (`VoskDetector.keyword`).
