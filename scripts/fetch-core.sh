#!/usr/bin/env bash
# Скачивает бинарники ядра и tun2socks для сборки APK.
# Запускается из CI и локально (Linux/macOS/WSL).
set -euo pipefail

MIHOMO_VERSION="${MIHOMO_VERSION:-v1.19.34}"
HEV_VERSION="${HEV_VERSION:-2.19.3}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNI="$ROOT/app/src/main/jniLibs"
MANIFEST="$ROOT/scripts/core.sha256"

mkdir -p "$LIBS" "$JNI/arm64-v8a" "$JNI/armeabi-v7a"

HEV_REL="app/libs/hev-socks5-tunnel.aar"
ARM64_REL="app/src/main/jniLibs/arm64-v8a/libmihomo.so"
ARMV7_REL="app/src/main/jniLibs/armeabi-v7a/libmihomo.so"

# Проверяем и свежескачанные, и уже лежащие на диске файлы: кэш не должен
# позволять собирать APK с подменённым ядром.
verify_artifact() {
  local rel="$1"
  local file="$ROOT/$1"
  local expected actual
  expected="$(awk -v key="$rel" '$1 !~ /^#/ && $2 == key { print tolower($1) }' "$MANIFEST")"
  if [ -z "$expected" ]; then
    echo "Нет SHA-256 для $rel в scripts/core.sha256" >&2
    return 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$file" | awk '{print tolower($1)}')"
  else
    actual="$(shasum -a 256 "$file" | awk '{print tolower($1)}')"
  fi
  if [ "$actual" != "$expected" ]; then
    echo "SHA-256 не совпал для $rel: ожидалось $expected, получено $actual" >&2
    rm -f "$file"
    return 1
  fi
}

echo "==> hev-socks5-tunnel $HEV_VERSION"
if [ -s "$LIBS/hev-socks5-tunnel.aar" ] && verify_artifact "$HEV_REL"; then
  echo "    уже на месте"
else
  curl -fL --retry 5 --retry-delay 2 \
    -o "$LIBS/hev-socks5-tunnel.aar" \
    "https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel.aar"
  verify_artifact "$HEV_REL"
fi

fetch_mihomo() {
  local abi="$1"
  local asset="$2"
  local rel="$3"
  local out="$ROOT/$3"
  if [ -s "$out" ] && verify_artifact "$rel"; then
    echo "    $abi: уже на месте"
    return
  fi
  local tmp
  tmp="$(mktemp -d)"
  echo "    $abi: $asset"
  curl -fL --retry 5 --retry-delay 2 \
    -o "$tmp/mihomo.gz" \
    "https://github.com/MetaCubeX/mihomo/releases/download/${MIHOMO_VERSION}/${asset}"
  gzip -dc "$tmp/mihomo.gz" > "$out"
  chmod +x "$out"
  rm -rf "$tmp"
  verify_artifact "$rel"
}

echo "==> mihomo $MIHOMO_VERSION"
fetch_mihomo "arm64-v8a" \
  "mihomo-android-arm64-v8-${MIHOMO_VERSION}.gz" \
  "$ARM64_REL"
fetch_mihomo "armeabi-v7a" \
  "mihomo-android-armv7-${MIHOMO_VERSION}.gz" \
  "$ARMV7_REL"

echo "==> Готово"
ls -lh "$LIBS/hev-socks5-tunnel.aar" "$JNI/arm64-v8a/libmihomo.so" "$JNI/armeabi-v7a/libmihomo.so"
