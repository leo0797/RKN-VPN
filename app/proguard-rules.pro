# WorkManager сохраняет имя класса воркера в своей БД и создаёт его через
# Class.forName. Если R8 переименует класс, после обновления старые записи
# перестанут находиться и фоновое обновление подписки тихо умрёт.
-keep class app.rkn.vpn.work.RefreshWorker { *; }

# hev-socks5-tunnel: JNI-обёртки и нативные методы. AAR уже приносит
# -keep class hev.htproxy.** { *; }, дублируем явно на случай смены AAR.
-keep class hev.htproxy.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Отчёты диагностики и логи ядра должны оставаться читаемыми: номер строки
# помогает понять, где именно упал ядро или приложение.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose, Kotlin metadata и consumer rules библиотек полагаются на атрибуты.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, Exceptions
