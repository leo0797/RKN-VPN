Что нового в 1.0.13:

• Приложение подписывается новым release-ключом вместо debug-сертификата. Из-за смены подписи один раз удалите старую версию и установите эту вручную — после этого обновления внутри приложения продолжат работать.
• Весь внешний трафик переведён на HTTPS. Локальный API ядра по-прежнему доступен только на 127.0.0.1.
• Подписки принимаются только по ссылкам https://.
• Проверка доступности узлов использует HTTPS.
• Обновление через приложение сохранено: уже скачанный APK по-прежнему используется повторно, если установку отменили.

What's new in 1.0.13:

• The app is now signed with a proper release key instead of a debug certificate. Because the signing key changed, uninstall the old version and install this one manually once — in-app updates will keep working afterwards.
• All external traffic is HTTPS-only. The local core API remains available on 127.0.0.1 only.
• Subscriptions now accept https:// links only.
• Node availability checks use HTTPS.
• In-app updates are preserved: an already downloaded APK is still reused if you cancel the installation.
