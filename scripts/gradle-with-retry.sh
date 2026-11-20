#!/usr/bin/env bash
# Повторяем только сетевой троттлинг репозиториев (HTTP 429).
# Ошибки компиляции/тестов не маскируются и не перезапускаются.
set -o pipefail

attempt=1
max_attempts=3
while true; do
  log="$(mktemp)"
  if ./gradlew "$@" 2>&1 | tee "$log"; then
    rm -f "$log"
    exit 0
  fi
  if grep -qE '429|Too Many Requests' "$log" && [ "$attempt" -lt "$max_attempts" ]; then
    echo "::warning::Maven repository throttled the request, retrying ($attempt/$max_attempts)"
    attempt=$((attempt + 1))
    rm -f "$log"
    sleep 30
    continue
  fi
  rm -f "$log"
  exit 1
done
