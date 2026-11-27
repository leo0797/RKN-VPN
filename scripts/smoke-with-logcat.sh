#!/usr/bin/env bash
# Runs instrumented smoke tests and always saves device diagnostics.
# The emulator runner executes each workflow script line in a separate shell,
# so this wrapper keeps the failure code while still collecting logcat.
set +e

chmod +x ./gradlew

api_level="$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"

{
  echo "SDK=$(adb shell getprop ro.build.version.sdk 2>&1)"
  echo "PAGE_SIZE=$(adb shell getconf PAGE_SIZE 2>&1)"
  echo "ABILIST=$(adb shell getprop ro.product.cpu.abilist 2>&1)"
  echo "MODEL=$(adb shell getprop ro.product.model 2>&1)"
} > device-info.txt 2>&1

run_smoke_tests() {
  # gradle-with-retry.sh уже повторяет сетевые/зависимостные сбои.
  bash scripts/gradle-with-retry.sh :app:connectedDebugAndroidTest -Prkn.splits.enabled=false --stacktrace
}

run_smoke_tests
rc=$?

# API 24 на GitHub-hosted эмуляторе иногда не успевает полностью подняться
# к моменту запуска instrumented-тестов. Один повтор только для этого API.
if [ "$rc" -ne 0 ] && [ "$api_level" = "24" ]; then
  echo "::warning::API 24 smoke test failed, retrying once"
  adb wait-for-device >/dev/null 2>&1 || true
  run_smoke_tests
  rc=$?
fi

adb logcat -b crash -d > logcat-crash.txt 2>&1 || true
adb logcat -d -s AndroidRuntime:E DEBUG:* *:F > logcat-fatal.txt 2>&1 || true
adb logcat -d > logcat.txt 2>&1 || true

# ANR-трейки содержат стек main thread и недоступны обычному shell.
# На эмуляторе adb root работает; на устройстве просто сохранится диагноз.
adb root >/dev/null 2>&1 || true
adb wait-for-device >/dev/null 2>&1 || true
{
  echo "=== ls -la /data/anr ==="
  adb shell ls -la /data/anr 2>&1 || true
  for name in $(adb shell ls /data/anr 2>/dev/null | tr -d '\r'); do
    case "$name" in
      anr_*|temp_anr_*) ;;
      *) continue ;;
    esac
    echo "=== /data/anr/$name ==="
    adb shell cat "/data/anr/$name" 2>&1 || true
  done
} > anr-traces.txt 2>&1

exit $rc
