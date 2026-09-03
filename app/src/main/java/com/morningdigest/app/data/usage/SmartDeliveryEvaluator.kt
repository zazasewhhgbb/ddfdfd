package com.morningdigest.app.data.usage

import android.content.Context
import com.morningdigest.app.data.prefs.AppSettings
import java.util.Calendar

/**
 * Decides whether the scheduled digest notification should be skipped for
 * this run - on top of (not instead of) the existing Sleep Mode window
 * check. Sleep Mode is a fixed time-of-day; Smart Delivery is about *why*
 * that fixed time might not be the right moment on this particular day:
 * it's the weekend, or the user is demonstrably already up and on their
 * phone. Both rules are opt-in and off by default.
 *
 * This only ever affects the *scheduled* (WorkManager-triggered) run - a
 * manual "Refresh"/"Notify Now" tap always goes through regardless, since
 * the user explicitly asked for it right then.
 */
object SmartDeliveryEvaluator {

    data class Decision(val skip: Boolean, val reason: String? = null)

    fun evaluate(context: Context, settings: AppSettings, now: Calendar = Calendar.getInstance()): Decision {
        if (settings.smartDeliverySkipWeekends) {
            val dow = now.get(Calendar.DAY_OF_WEEK)
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) {
                return Decision(skip = true, reason = "Skipped - weekend (Smart Delivery)")
            }
        }
        if (settings.smartDeliverySkipIfAlreadyAwake) {
            if (UsageStatsInsightProvider.isRecentlyActive(context, withinMinutes = SKIP_IF_ACTIVE_WITHIN_MINUTES)) {
                return Decision(skip = true, reason = "Skipped - already active on phone (Smart Delivery)")
            }
        }
        return Decision(skip = false)
    }

    // How recently the phone must have been used to count as "already
    // awake" - long enough to catch someone who unlocked a couple minutes
    // before the scheduled time, short enough not to skip based on
    // yesterday's usage.
    private const val SKIP_IF_ACTIVE_WITHIN_MINUTES = 20
}
