Что нового в 1.0.22:

ВАЖНО: сменился сертификат подписи. Перед установкой этой версии удалите старое приложение (настройки сохранятся в резервной копии). Это одноразовая процедура.

• Обновления скачиваются только с GitHub и проверяются по SHA-256 из описания релиза и по подписи APK.
• Ссылка на подписку и параметры узлов хранятся в зашифрованном виде через Android Keystore. Переход HTTPS → HTTP запрещён, для http:// показывается предупреждение.
• Включён DNS ядра с DoH и proxy-server-nameserver; добавлены российские домены в ru_direct.
• Deep links vless://, vmess://, clash://install-config, happ://add и rknvpn://; добавление подписки из буфера и по QR прямо на главном экране.
• Раздельное туннелирование «всё через VPN, кроме выбранных», статус системного kill switch и подсказка по батарее для Xiaomi/Huawei/Samsung.
• Локальный crash-лог в диагностике; VPN помечается как платное соединение; Actions в CI закреплены по SHA, добавлены lint и прогон на API 24–28.

What's new in 1.0.22:

IMPORTANT: the signing certificate has changed. Uninstall the old app before installing this version (back up your settings first). This is a one-time step.

• Updates are downloaded from GitHub only and verified with the release SHA-256 and the APK signature.
• The subscription token and node secrets are encrypted with Android Keystore. HTTPS → HTTP redirects are blocked and http:// links show a warning.
• Enabled core DNS with DoH and proxy-server-nameserver; expanded the ru_direct domain list.
• Deep links vless://, vmess://, clash://install-config, happ://add and rknvpn://; add a subscription from the clipboard or a QR code right from Home.
• Split tunneling "everything through VPN except selected apps", system kill switch status and a battery hint for Xiaomi/Huawei/Samsung.
• Local crash log in diagnostics; VPN is marked as metered; CI actions are SHA-pinned, with lint and API 24–28 smoke tests added.
