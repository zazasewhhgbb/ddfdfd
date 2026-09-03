package com.morningdigest.app.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.morningdigest.app.data.model.ScreenTimeInsight
import java.util.Calendar
import java.util.Locale

/**
 * Reads the user's own phone-activity history via [UsageStatsManager] to
 * power two things:
 *
 *  1. [lateNightOpenInsight] - the "you opened your phone at 2 AM 3 nights
 *     this week" dashboard/notification nudge (Settings > Screen-Time
 *     Insight). A genuinely different kind of signal than the rest of the
 *     digest: it's about the user's own behaviour, not an external feed.
 *  2. [isRecentlyActive] - "is the user already awake and on their phone
 *     right now?", used by Smart Delivery to skip the scheduled notification
 *     instead of firing at a fixed time regardless of whether it's needed.
 *
 * Both require the special PACKAGE_USAGE_STATS permission. Unlike a normal
 * runtime permission, this can only be granted from a dedicated system
 * Settings screen - there's no in-app dialog for it - so [hasUsageAccess] /
 * [usageAccessSettingsIntent] exist to check for and deep-link to that
 * screen from our own Settings UI.
 */
object UsageStatsInsightProvider {

    // Late-night window scanned for "phone opened" events: 00:00-05:00.
    private const val WINDOW_START_HOUR = 0
    private const val WINDOW_END_HOUR_EXCLUSIVE = 5
    private const val DEFAULT_LOOKBACK_DAYS = 7
    // A night only counts once its first late-night open is at least this
    // many minutes after the previously counted one, so one restless
    // stretch of checking the phone every few minutes doesn't get counted
    // as several separate incidents.
    private const val MIN_GAP_MINUTES = 20
    // Below this many distinct nights, the pattern isn't really "a few
    // nights this week" yet - not worth surfacing as a nudge.
    private const val MIN_NIGHTS_TO_SURFACE = 2

    /** True if the app currently has the special "Usage access" permission granted. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Deep-links straight to this app's row in the system "Usage access" settings screen. */
    fun usageAccessSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Scans the last [lookbackDays] days of usage events for late-night phone
     * opens (any app moving to the foreground between midnight and 5am) and,
     * if that happened on [MIN_NIGHTS_TO_SURFACE]+ distinct nights, returns a
     * line like "You opened your phone at 2 AM 3 nights this week." Returns
     * `ScreenTimeInsight(available = false)` when usage access isn't granted,
     * the event query fails, or nothing notable was found - callers can just
     * check [ScreenTimeInsight.available] rather than handling nulls/errors.
     */
    fun lateNightOpenInsight(
        context: Context,
        lookbackDays: Int = DEFAULT_LOOKBACK_DAYS
    ): ScreenTimeInsight {
        if (!hasUsageAccess(context)) return ScreenTimeInsight(available = false)
        return runCatching {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - lookbackDays * 24 * 60 * 60 * 1000L
            val events = usageStatsManager.queryEvents(start, end)

            val nightKeys = mutableSetOf<String>()
            val hourCounts = mutableMapOf<Int, Int>()
            var lastCountedMillis = -1L
            val event = UsageEvents.Event()
            val cal = Calendar.getInstance()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (!isForegroundEvent(event)) continue

                cal.timeInMillis = event.timeStamp
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                if (hour < WINDOW_START_HOUR || hour >= WINDOW_END_HOUR_EXCLUSIVE) continue

                // Collapse a burst of opens a few minutes apart into one occurrence.
                if (lastCountedMillis >= 0 && event.timeStamp - lastCountedMillis < MIN_GAP_MINUTES * 60 * 1000L) {
                    lastCountedMillis = event.timeStamp
                    continue
                }
                lastCountedMillis = event.timeStamp

                // A 2am Tuesday open belongs to "Monday night", not its own
                // "Tuesday" - roll early-morning hours back onto the previous
                // calendar day before keying the night.
                val nightCal = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
                nightKeys.add("%04d-%03d".format(Locale.US, nightCal.get(Calendar.YEAR), nightCal.get(Calendar.DAY_OF_YEAR)))
                hourCounts[hour] = (hourCounts[hour] ?: 0) + 1
            }

            if (nightKeys.size < MIN_NIGHTS_TO_SURFACE) return@runCatching ScreenTimeInsight(available = false)

            val commonHour = hourCounts.maxByOrNull { it.value }?.key ?: return@runCatching ScreenTimeInsight(available = false)
            val nightsWord = if (nightKeys.size == 1) "night" else "nights"
            val text = "You opened your phone at ${formatHour(commonHour)} ${nightKeys.size} $nightsWord this week."

            ScreenTimeInsight(
                available = true,
                text = text,
                nightsCount = nightKeys.size,
                lookbackDays = lookbackDays
            )
        }.getOrElse { ScreenTimeInsight(available = false) }
    }

    /**
     * True if the phone has genuinely been used (any app brought to the
     * foreground) within the last [withinMinutes] minutes. Used by Smart
     * Delivery to recognize "the user is already awake and on their phone"
     * and skip the scheduled digest notification rather than piling a
     * redundant buzz on top of what they're already looking at.
     */
    fun isRecentlyActive(context: Context, withinMinutes: Int = 15): Boolean {
        if (!hasUsageAccess(context)) return false
        return runCatching {
            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - withinMinutes * 60 * 1000L
            val events = usageStatsManager.queryEvents(start, end)
            val event = UsageEvents.Event()
            var active = false
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (isForegroundEvent(event)) {
                    active = true
                    break
                }
            }
            active
        }.getOrDefault(false)
    }

    private fun isForegroundEvent(event: UsageEvents.Event): Boolean =
        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)

    /** 0 -> "12 AM", 2 -> "2 AM", 13 -> "1 PM", etc. */
    private fun formatHour(hour24: Int): String {
        val period = if (hour24 < 12) "AM" else "PM"
        val hour12 = when (val h = hour24 % 12) {
            0 -> 12
            else -> h
        }
        return "$hour12 $period"
    }
}
