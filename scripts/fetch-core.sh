#!/usr/bin/env bash
# Скачивает бинарники ядра и tun2socks для сборки APK.
# Запускается из CI и локально (Linux/macOS/WSL).
set -euo pipefail

MIHOMO_VERSION="${MIHOMO_VERSION:-v1.19.34}"
HEV_VERSION="${HEV_VERSION:-2.19.3}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/app/libs"
JNI="$ROOT/app/src/main/jniLibs"

mkdir -p "$LIBS" "$JNI/arm64-v8a" "$JNI/armeabi-v7a"

echo "==> hev-socks5-tunnel $HEV_VERSION"
if [ ! -s "$LIBS/hev-socks5-tunnel.aar" ]; then
  curl -fL --retry 5 --retry-delay 2 \
    -o "$LIBS/hev-socks5-tunnel.aar" \
    "https://github.com/heiher/hev-socks5-tunnel/releases/download/${HEV_VERSION}/hev-socks5-tunnel.aar"
fi

fetch_mihomo() {
  local abi="$1"
  local asset="$2"
  local out="$3"
  if [ -s "$out" ]; then
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
}

echo "==> mihomo $MIHOMO_VERSION"
fetch_mihomo "arm64-v8a" \
  "mihomo-android-arm64-v8-${MIHOMO_VERSION}.gz" \
  "$JNI/arm64-v8a/libmihomo.so"
fetch_mihomo "armeabi-v7a" \
  "mihomo-android-armv7-${MIHOMO_VERSION}.gz" \
  "$JNI/armeabi-v7a/libmihomo.so"

echo "==> Готово"
ls -lh "$LIBS/hev-socks5-tunnel.aar" "$JNI/arm64-v8a/libmihomo.so" "$JNI/armeabi-v7a/libmihomo.so"
