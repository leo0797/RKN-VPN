package app.rkn.vpn.core

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Сверка подписи скачанного APK с подписью установленного приложения.
 *
 * Это обязательная проверка перед установкой обновления: без неё злоумышленник
 * мог бы подсунуть APK, подписанный другим ключом. Android отказался бы
 * обновить приложение, но пользователь увидел бы только ошибку установщика,
 * а подмена файла на пути от GitHub до устройства прошла бы незамеченной.
 */
object ApkSignature {

    /** SHA-256 сертификатов установленной версии приложения. */
    fun installed(context: Context): Set<String> = certificates(context, null)

    /** SHA-256 сертификатов APK на диске (пустой набор, если файл не читается). */
    fun of(context: Context, file: File): Set<String> = certificates(context, file)

    fun matches(context: Context, file: File): Boolean {
        val expected = installed(context)
        val actual = of(context, file)
        return expected.isNotEmpty() && actual.isNotEmpty() && expected.any { it in actual }
    }

    @Suppress("DEPRECATION")
    private fun certificates(context: Context, archive: File?): Set<String> {
        val pm = context.packageManager
        val signatures = LinkedHashSet<Signature>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = if (archive == null) {
                runCatching {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                }.getOrNull()?.signingInfo
            } else {
                runCatching {
                    pm.getPackageArchiveInfo(
                        archive.absolutePath,
                        PackageManager.GET_SIGNING_CERTIFICATES
                    )
                }.getOrNull()?.signingInfo
            }
            if (info != null) {
                val found = if (info.hasMultipleSigners()) {
                    info.apkContentsSigners
                } else {
                    info.signingCertificateHistory
                }
                signatures.addAll(found)
            }
        }

        if (signatures.isEmpty()) {
            val found = if (archive == null) {
                runCatching {
                    pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                }.getOrNull()?.signatures
            } else {
                runCatching {
                    pm.getPackageArchiveInfo(archive.absolutePath, PackageManager.GET_SIGNATURES)
                }.getOrNull()?.signatures
            }
            if (found != null) signatures.addAll(found)
        }

        return signatures.mapNotNull { signature ->
            runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }
}
