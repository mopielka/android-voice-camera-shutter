#!/usr/bin/env bash
# Connects to the phone over Wi-Fi so no cable is needed.
#
# One-off pairing (phone and Mac on the same Wi-Fi):
#   Settings -> Developer options -> Wireless debugging -> on
#   "Pair device with pairing code" -> note the IP:PORT and the 6-digit code
#   ./tools/adb-wireless.sh pair 192.168.1.42:37123 123456
#
# Every session after that:
#   ./tools/adb-wireless.sh
#
# The pairing survives reboots, but the *connect* port changes, so this script
# discovers it over mDNS instead of asking you to read it off the screen again.
set -euo pipefail

ADB="${ADB:-adb}"
STATE_FILE="${HOME}/.android/voice-shutter-wireless"

pair() {
    local endpoint="$1" code="$2"
    "${ADB}" pair "${endpoint}" "${code}"
    echo "${endpoint%%:*}" > "${STATE_FILE}"
    echo "Sparowano. Adres zapisany w ${STATE_FILE}"
    connect
}

connect() {
    # adb advertises paired devices over mDNS; this is the port that changes.
    local svc
    svc="$("${ADB}" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $3; exit}')"

    if [[ -n "${svc}" ]]; then
        echo "Znaleziono przez mDNS: ${svc}"
        "${ADB}" connect "${svc}"
    elif [[ -f "${STATE_FILE}" ]]; then
        local ip
        ip="$(cat "${STATE_FILE}")"
        echo "mDNS milczy — próbuję zapisany adres ${ip}:5555"
        "${ADB}" connect "${ip}:5555"
    else
        echo "Brak sparowanego urządzenia. Uruchom najpierw:" >&2
        echo "  $0 pair <IP:PORT> <KOD>" >&2
        exit 1
    fi

    "${ADB}" devices -l
}

case "${1:-connect}" in
    pair)    pair "${2:?podaj IP:PORT z ekranu parowania}" "${3:?podaj 6-cyfrowy kod}" ;;
    connect) connect ;;
    *)       echo "Użycie: $0 [connect | pair <IP:PORT> <KOD>]" >&2; exit 1 ;;
esac
