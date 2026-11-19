Что нового в 1.0.16:

• Бинарники ядра (mihomo и hev-socks5-tunnel) проверяются по зафиксированным SHA-256 — и после загрузки, и из кэша. Подменённый файл приведёт к ошибке сборки.
• При падении ядра или tun2socks TUN больше не закрывается сразу: приложение делает до 5 попыток перезапуска с задержкой 1–16 с, и всё это время трафик не уходит мимо VPN. Если восстановить не удалось, показывается уведомление об ошибке.
• После подключения выбранный узел проверяется через API ядра; если он не отвечает, на главном экране появляется соответствующая надпись.
• Намерение пользователя включить VPN переживает перезапуск процесса; после отзыва разрешения или ручного отключения автоподключение не возвращается.
• Конфиг tun2socks с паролем сессии удаляется при остановке. Исправлено предупреждение об остатке трафика.

What's new in 1.0.16:

• Core binaries (mihomo and hev-socks5-tunnel) are verified against pinned SHA-256 hashes, both after download and when cached. A tampered file fails the build.
• If the core or tun2socks dies, the TUN stays open: up to 5 restarts with 1–16 s backoff keep traffic from leaking, followed by an error notification if recovery fails.
• The selected node is checked through the core API after connecting; the home screen reports it when the node does not respond.
• The user's intent to keep VPN on survives process death; revoking permission or disconnecting manually prevents auto-reconnect.
• The tun2socks config with session credentials is deleted on stop. Fixed the low-traffic warning text.
