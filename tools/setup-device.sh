#!/usr/bin/env bash
# Grants everything the app needs via ADB, so none of it has to be clicked through.
# Safe to re-run; run it again after a reboot if MagicOS has reset something.
set -euo pipefail

PKG="dev.opielka.voiceshutter"
SERVICE="${PKG}/${PKG}.ShutterAccessibilityService"
ADB="${ADB:-adb}"

if ! "${ADB}" get-state >/dev/null 2>&1; then
    echo "Brak podłączonego urządzenia. Podłącz telefon i włącz debugowanie USB." >&2
    exit 1
fi

if ! "${ADB}" shell pm list packages | grep -q "package:${PKG}"; then
    echo "Aplikacja ${PKG} nie jest zainstalowana. Uruchom najpierw ./gradlew installDebug" >&2
    exit 1
fi

echo "==> Zdejmuję blokadę Restricted Settings"
"${ADB}" shell appops set "${PKG}" ACCESS_RESTRICTED_SETTINGS allow || \
    echo "    (nieobsługiwane na tej wersji Androida — pomijam)"

echo "==> Nadaję uprawnienia"
"${ADB}" shell pm grant "${PKG}" android.permission.RECORD_AUDIO
"${ADB}" shell pm grant "${PKG}" android.permission.POST_NOTIFICATIONS

echo "==> Wyjątek od optymalizacji baterii"
"${ADB}" shell dumpsys deviceidle whitelist "+${PKG}" >/dev/null

echo "==> Włączam usługę dostępności"
# Dopisujemy się do istniejącej listy — nadpisanie wyłączyłoby inne usługi dostępności.
CURRENT="$("${ADB}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
if [[ "${CURRENT}" == "null" || -z "${CURRENT}" ]]; then
    UPDATED="${SERVICE}"
elif [[ ":${CURRENT}:" == *":${SERVICE}:"* ]]; then
    UPDATED="${CURRENT}"
else
    UPDATED="${CURRENT}:${SERVICE}"
fi
"${ADB}" shell settings put secure enabled_accessibility_services "${UPDATED}"
"${ADB}" shell settings put secure accessibility_enabled 1

echo "==> Weryfikacja"
sleep 3
# A fresh install leaves the entry in settings but the system does not always bind the
# service, so checking the settings value (or dumpsys accessibility, which echoes it)
# reports success while nothing runs. Only a live ServiceRecord proves it.
running() { "${ADB}" shell dumpsys activity services "${PKG}" 2>/dev/null | grep -q ShutterAccessibilityService; }

if ! running; then
    echo "    Nie wystartowała — przeładowuję wpis"
    "${ADB}" shell settings put secure enabled_accessibility_services "${CURRENT}" >/dev/null 2>&1
    sleep 2
    "${ADB}" shell settings put secure enabled_accessibility_services "${UPDATED}" >/dev/null 2>&1
    "${ADB}" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1
    sleep 3
fi

if running; then
    echo "    Usługa dostępności działa."
else
    cat <<'MANUAL'
    Usługa NIE została uruchomiona przez system.
    Android potrafi zignorować wpis zrobiony przez ADB. Zrób to ręcznie:
      1. Ustawienia -> Aplikacje -> Voice Shutter -> menu "..." -> Zezwól na ograniczone ustawienia
      2. Ustawienia -> Dostępność -> Voice Shutter -> włącz
MANUAL
fi

cat <<'HONOR'

==> Krok, którego ADB nie załatwi (Honor/MagicOS)
    Ustawienia -> Bateria -> Uruchamianie aplikacji -> Voice Shutter -> Zarządzaj ręcznie
    Włącz wszystkie trzy przełączniki (autostart, uruchamianie pośrednie, działanie w tle).
    Bez tego PowerGenie ubije nasłuch po kilku godzinach.
HONOR
