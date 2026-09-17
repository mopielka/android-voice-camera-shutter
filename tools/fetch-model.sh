#!/usr/bin/env bash
# Downloads the Vosk speech model into app/src/main/assets/.
# Kept out of git (~40 MB) — run this once before the first build.
set -euo pipefail

MODEL_NAME="vosk-model-small-en-us-0.15"
MODEL_URL="https://alphacephei.com/vosk/models/${MODEL_NAME}.zip"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${ROOT}/app/src/main/assets/model-en-us"

if [[ -f "${TARGET}/am/final.mdl" ]]; then
    echo "Model już jest w ${TARGET}"
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

echo "Pobieram ${MODEL_NAME}..."
curl -fsSL "${MODEL_URL}" -o "${TMP}/model.zip"
unzip -q "${TMP}/model.zip" -d "${TMP}"

mkdir -p "$(dirname "${TARGET}")"
rm -rf "${TARGET}"
mv "${TMP}/${MODEL_NAME}" "${TARGET}"

echo "Model rozpakowany do ${TARGET}"
