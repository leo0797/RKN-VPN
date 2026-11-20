Что нового в 1.0.25:

• Исправлен вылет при добавлении подписки на Android 12+: Keystore больше не получает собственный IV — теперь IV генерируется шифром, как требует Android Keystore.
• Добавлена регрессионная проверка защищённого хранилища.

What's new in 1.0.25:

• Fixed a crash when adding a subscription on Android 12+: the Keystore cipher now generates its own IV instead of receiving one from the app.
• Added a regression test for secure storage.
