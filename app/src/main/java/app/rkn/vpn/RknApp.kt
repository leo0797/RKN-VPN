package app.rkn.vpn

import android.app.Application
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.util.AppLanguage
import app.rkn.vpn.vpn.VpnController

class RknApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.apply(this, Prefs(this).language)
        VpnController.init(this)
    }
}
