package app.rkn.vpn

import android.app.Application
import app.rkn.vpn.core.ApkInstaller
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.util.AppLanguage
import app.rkn.vpn.util.CrashLog
import app.rkn.vpn.vpn.VpnController
import app.rkn.vpn.work.RefreshWorker

class RknApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Файл обновления больше не нужен после перезапуска приложения.
        ApkInstaller.cleanup(this)
        AppLanguage.apply(this, Prefs(this).language)
        VpnController.init(this)
        // UPDATE сохраняет время следующего запуска при перепланировании.
        // Robolectric отключает WorkManagerInitializer, поэтому enqueue не должен
        // ронять запуск приложения (и в проде сбой шедулера не критичен).
        runCatching { RefreshWorker.enqueue(this) }
    }
}
