package app.rkn.vpn.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.rkn.vpn.data.Prefs
import app.rkn.vpn.vpn.VpnController
import java.util.concurrent.TimeUnit

/**
 * Периодическое обновление подписки в фоне — даже если приложение не открывают.
 * Расписание берётся из `updateIntervalHours` (приходит от сервера подписки).
 * При отсутствии сети WorkManager сам дождётся подключения.
 */
class RefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Воркер может запуститься в новом процессе после смерти приложения.
        VpnController.init(applicationContext)
        val prefs = Prefs(applicationContext)
        val hasAnyUrl = prefs.profiles.any { prefs.profileUrl(it.id).isNotBlank() }
        if (!hasAnyUrl) return Result.success()

        // Ошибку не возвращаем как Result.failure(): это навсегда остановило бы periodic
        // work. Следующий запланированный запуск попробует снова.
        runCatching { VpnController.refreshAllProfilesBackground() }
        return Result.success()
    }
    companion object {
        private const val UNIQUE_NAME = "subscription_refresh"

        fun enqueue(context: Context) {
            val hours = Prefs(context).updateIntervalHours.coerceIn(1, 168).toLong()
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(hours, TimeUnit.HOURS)
                .setInitialDelay(hours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
