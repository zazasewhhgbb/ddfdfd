package com.morningdigest.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.usage.SmartDeliveryEvaluator
import com.morningdigest.app.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs once a day (or on-demand): fetches weather/bitcoin/currency/news in
 * parallel, then delivers the result as a rich local notification (the
 * morning brief itself — no email/SMTP involved) and stores it in history.
 */
class MorningDigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MorningDigestApp
        val container = app.container

        return@withContext try {
            val settings = container.settingsRepository.currentSettings()
            val rawReport = container.digestRepository.buildFreshReport(settings)

            var finalReport = rawReport

            val smartDeliveryDecision = if (inputData.getBoolean(KEY_IS_SCHEDULED, false)) {
                SmartDeliveryEvaluator.evaluate(applicationContext, settings)
            } else {
                // Manual "Refresh"/"Notify Now" always goes through - the
                // user explicitly asked for it right now, so Smart Delivery
                // (which only exists to second-guess an *automatic* fire)
                // doesn't apply.
                SmartDeliveryEvaluator.Decision(skip = false)
            }

            if (inputData.getBoolean(KEY_SEND_NOTIFICATION, true) &&
                settings.notificationsEnabled &&
                !settings.isWithinSleepMode() &&
                !smartDeliveryDecision.skip
            ) {
                val posted = NotificationHelper.postDigest(
                    applicationContext, rawReport, settings.city, settings.userName
                )
                finalReport = if (posted) {
                    rawReport.copy(notificationSent = true, notificationError = null)
                } else {
                    rawReport.copy(
                        notificationSent = false,
                        notificationError = "Notification permission isn't granted. Enable notifications for The Brief in system settings."
                    )
                }
            }

            container.digestRepository.saveReport(finalReport)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_SEND_NOTIFICATION = "send_notification"
        // True only for the periodic (WorkManager-scheduled) runs enqueued by
        // WorkScheduler.scheduleDaily/scheduleInterval - false/absent for a
        // manual Refresh/Notify Now (WorkScheduler.runNow), so Smart Delivery
        // never second-guesses something the user just explicitly asked for.
        const val KEY_IS_SCHEDULED = "is_scheduled"
        const val UNIQUE_PERIODIC_NAME = "morning_digest_daily"
        const val UNIQUE_ONE_TIME_NAME = "morning_digest_manual"
    }
}
