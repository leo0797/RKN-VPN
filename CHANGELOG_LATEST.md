Что нового в 1.1.1:

• Входящие ссылки (vless, clash, happ, rknvpn) применяются только после подтверждения: диалог показывает источник и число узлов.
• Импорт из ссылки создаёт отдельный профиль, не заменяет активный и не включает автоподключение.
• Перед публикацией CI сверяет сертификат подписанного APK с эталонным отпечатком.
• Smoke-тест на API 24 повторяется один раз при падении эмулятора.

What's new in 1.1.1:

• Incoming links (vless, clash, happ, rknvpn) are applied only after confirmation: the dialog shows the source and node count.
• Link import creates a separate profile, keeps the active one and never turns on auto-connect.
• CI verifies the signed APK certificate against the pinned fingerprint before publishing.
• API 24 smoke test is retried once on an emulator failure.
