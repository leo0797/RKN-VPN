# Правила для ИИ-агентов и контрибьюторов

RKN VPN — Android VPN-клиент (Kotlin, Jetpack Compose, ядро mihomo как отдельный процесс,
hev-socks5-tunnel как TUN). Приложение обновляется само из GitHub Releases, поэтому
каждый релиз сразу попадает на устройства пользователей. Относись к изменениям соответственно.

## Сборка и проверки

```bash
bash scripts/fetch-core.sh                          # скачать mihomo и hev (SHA-256 в scripts/core.sha256)
./gradlew :app:testDebugUnitTest :app:lintDebug     # перед каждым PR
```

UI- и smoke-тесты на эмуляторах (API 24–28, 31, 34, 35) запускает CI. Если проверить
что-то локально нельзя, прямо напиши об этом в описании PR.

## Ветки и релизы

- **Не пушь в `main`.** Работай в ветке `fix/…`, `feat/…` или `chore/…` и открывай Pull Request.
- Один PR — одна задача. Описание: что изменено, зачем, как проверено, что не проверено.
- **Не создавай теги `v*` и релизы.** Тег запускает публикацию подписанного APK, которая
  уйдёт пользователям через автообновление. Релиз выпускает только владелец.
- Не меняй `versionCode`/`versionName` и `CHANGELOG*` в обычных PR — это делает владелец при релизе.

## Секреты и подпись

- Никогда не добавляй в репозиторий keystore, пароли, токены, ссылки подписок. `*.keystore` и `*.jks` в `.gitignore`.
- Не меняй `signingConfigs`, имена секретов и `permissions` в workflow без явной просьбы владельца.
- Ключ подписи хранится только в GitHub Secrets (`SIGNING_*`).
- Не логируй и не показывай в UI/отчётах ссылку подписки, HWID, пароли узлов и секреты сессии.

## Файлы, где ошибка бьёт по безопасности (нужен внимательный ревью)

`core/ApkSignature.kt`, `core/ApkInstaller.kt`, `core/UpdateChecker.kt`, `data/SecureStorage.kt`,
`data/SubscriptionRepository.kt`, `core/ConfigBuilder.kt`, `vpn/RknVpnService.kt`,
`AndroidManifest.xml`, `res/xml/network_security_config.xml`, `.github/workflows/*`,
`scripts/fetch-core.sh`, `scripts/core.sha256`.

При правке этих файлов в описании PR отдельно напиши, что изменилось и почему это безопасно.

## Код

- Новые строки интерфейса добавляй и в `res/values`, и в `res/values-en`.
- К изменению логики добавляй unit-тест (JUnit, Robolectric — см. `app/src/test`).
- Версии ядра (mihomo, hev) обновляй только вместе с `scripts/core.sha256` и проверкой хеша.
- Не добавляй внешние зависимости и сетевые обращения без необходимости: приложение не должно
  отправлять данные пользователя никуда, кроме его собственной подписки и GitHub Releases.
