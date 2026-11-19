package app.rkn.vpn

import android.content.Context
import androidx.activity.ComponentActivity
import app.rkn.vpn.util.AppLanguage

/** Базовая Activity: применяет сохранённый язык до создания ресурсов Activity. */
abstract class BaseActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }
}
