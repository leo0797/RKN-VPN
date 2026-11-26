# Политика безопасности / Security Policy

## Поддерживаемые версии / Supported versions

| Версия / Version | Поддерживается / Supported |
| --- | --- |
| Последний релиз / Latest release | ✅ |
| Более старые релизы / Older releases | ❌ |

Исправления безопасности выходят в виде нового релиза. Если вы используете старую
версию, обновитесь до последней перед сообщением о проблеме.

Security fixes are shipped as a new release. If you are on an older version,
please update to the latest one before reporting an issue.

## Как проверить подпись APK / How to verify the APK signature

Скачивайте APK только со страницы официальных релизов GitHub и перед установкой сверяйте отпечаток сертификата подписи:

Download APKs only from the official GitHub Releases page and verify the signing certificate fingerprint before installing:

```bash
apksigner verify --print-certs RKN-VPN-<version>-<abi>.apk
```

Актуальный SHA-256 отпечаток сертификата подписи (Signer #1):  
Current signing certificate SHA-256 fingerprint (Signer #1):

`fa8dc8198a713b10b1eb8e6faac429db8c2b607184a00764423d86cb017826cd`

Если отпечаток отличается или команда сообщает о проблеме с подписью, не устанавливайте файл.  
If the fingerprint differs or the tool reports a signature problem, do not install the file.

## Сообщить об уязвимости / Report a vulnerability

Не создавайте публичный Issue для уязвимостей. Вместо этого используйте вкладку
**Security → Report a vulnerability** в репозитории проекта: отчет будет виден
только поддерживающим.

Please do not open a public Issue for security vulnerabilities. Use the
**Security → Report a vulnerability** tab in the project repository instead —
the report is only visible to maintainers.

В сообщении укажите:

- версию приложения и Android;
- шаги воспроизведения;
- ожидаемое и фактическое поведение;
- при необходимости — лог ядра из раздела «Диагностика».

Please include:

- app version and Android version;
- steps to reproduce;
- expected and actual behavior;
- core logs from the Diagnostics section if relevant.

## Что считается уязвимостью / In scope

- Утечка трафика мимо VPN (закрытый TUN, обход туннеля).
- Утечка учётных данных подписки, локальных паролей или HWID.
- Подмена APK или канала обновления.
- Выполнение произвольного кода через разбор подписки/конфига.

- Traffic leaking outside the VPN tunnel.
- Leaking subscription credentials, local proxy passwords or HWID.
- APK or update channel tampering.
- Arbitrary code execution via subscription/config parsing.

## Что не считается уязвимостью / Out of scope

- Наличие VPN-интерфейса видно другим приложениям: Android не позволяет скрыть
  этот факт без root (см. сноску в настройках).
- Поведение сетевого провайдера или самой панели подписки.
- Отсутствие проверок root / Play Integrity в приложении.

- The existence of a VPN interface is visible to other apps — Android does not
  allow hiding it without root (see the note in Settings).
- Behavior of the network operator or of the subscription panel itself.
- Absence of root or Play Integrity checks in the app.
