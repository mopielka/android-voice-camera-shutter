#!/usr/bin/env bash
# Dumps the UI tree of whatever is on screen and highlights shutter-button candidates.
# Open the camera app, then run this to find the right resource-id.
set -euo pipefail

ADB="${ADB:-adb}"
OUT="${1:-docs/camera-ui-dump.xml}"

"${ADB}" shell uiautomator dump /sdcard/window_dump.xml >/dev/null
"${ADB}" pull /sdcard/window_dump.xml "${OUT}" >/dev/null
"${ADB}" shell rm /sdcard/window_dump.xml

echo "Zrzut zapisany w ${OUT}"
echo
echo "Kandydaci na spust migawki:"
grep -oE '<node[^>]*(shutter|capture|migawk|zdj[^"]*)[^>]*>' "${OUT}" \
    | grep -oE 'resource-id="[^"]*"|content-desc="[^"]*"|bounds="[^"]*"|clickable="[^"]*"' \
    | paste - - - - 2>/dev/null \
    || echo "  Brak oczywistych trafień — przejrzyj ${OUT} ręcznie."
