#!/usr/bin/env bash
# Раз в неделю сравнивает закреплённую версию mihomo с последним релизом
# upstream. Dependabot не следит за версиями, которые заданы в shell-скрипте,
# поэтому обновление ядра иначе легко пропустить.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PINNED="$(sed -n 's/^MIHOMO_VERSION="${MIHOMO_VERSION:-\([^}]*\)}"/\1/p' "$ROOT/scripts/fetch-core.sh" | head -n 1)"
if [ -z "$PINNED" ]; then
  echo "Не удалось прочитать MIHOMO_VERSION из fetch-core.sh" >&2
  exit 1
fi

LATEST="$(curl -fsSL --retry 3 \
  -H 'Accept: application/vnd.github+json' \
  -H "User-Agent: ${GITHUB_REPOSITORY:-rkn-vpn}-core-watch" \
  https://api.github.com/repos/MetaCubeX/mihomo/releases/latest \
  | jq -r '.tag_name // empty')"
if [ -z "$LATEST" ]; then
  echo "Не удалось получить последнюю версию mihomo" >&2
  exit 1
fi

echo "Pinned: $PINNED"
echo "Latest: $LATEST"
if [ "$PINNED" = "$LATEST" ]; then
  echo "Ядро актуально"
  exit 0
fi

TITLE="Обновить ядро mihomo до $LATEST"
BODY="Закреплённая версия: \`$PINNED\`. Последний релиз upstream: \`$LATEST\`.

Что нужно сделать:
1. Обновить \`MIHOMO_VERSION\` в \`scripts/fetch-core.sh\`.
2. Пересобрать бинарники и обновить SHA-256 в \`scripts/core.sha256\`.
3. Проверить, что конфиг из \`ConfigBuilder\` принимается новым ядром."

EXISTING="$(gh issue list --state open --search "$TITLE in:title" --json number --jq '.[0].number // empty')"
if [ -n "$EXISTING" ]; then
  gh issue comment "$EXISTING" --body "Проверка от $(date -u +%F): upstream всё ещё на \`$LATEST\`."
  echo "Issue #$EXISTING обновлён"
else
  gh issue create --title "$TITLE" --body "$BODY"
  echo "Создано issue: $TITLE"
fi
