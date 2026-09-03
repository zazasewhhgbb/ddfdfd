package com.morningdigest.app.data.facts

import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.model.NewsHeadline
import com.morningdigest.app.data.prefs.MascotCharacter
import kotlin.math.abs

/**
 * How an analyst is "feeling" right now, purely as a function of real data
 * already on the [DigestReport] - never invented, never persisted. Ordered
 * from best to worst so two moods can be compared with [MascotMood.ordinal]
 * (used by [MascotMoodEngine] to pick the more urgent of two signals).
 */
enum class MascotMood(val emoji: String) {
    EXCITED("🤩"),
    HAPPY("🙂"),
    CALM("😌"),
    CONCERNED("😟"),
    ALERT("⚠️")
}

/**
 * Derives each analyst's [MascotMood] from the same [DigestReport] their
 * briefing text is built from (see [AssistantReportBuilder]) - this is the
 * "how do they look" counterpart to that "what do they say" builder, so the
 * portrait's expression reinforces rather than contradicts the text next to
 * it. Two kinds of signal feed in:
 *
 * - Ambient weather severity (storms, official alerts, extreme temps) - a
 *   rough morning affects every analyst's day, not just the weather card, so
 *   a severe reading pulls everyone toward [MascotMood.ALERT] regardless of
 *   their own domain.
 * - Domain sentiment - simple keyword scoring over that analyst's own top
 *   headlines for the news-driven characters, and real price-move magnitude
 *   (in the direction that matters to that character's persona) for the
 *   market-driven ones.
 *
 * Deliberately not ML/NLP - a small keyword list is transparent, offline,
 * and good enough for a decorative mood badge; it doesn't need to be a
 * precise sentiment classifier.
 */
object MascotMoodEngine {

    fun moodFor(character: MascotCharacter, report: DigestReport?): MascotMood {
        if (report == null) return MascotMood.CALM

        val weatherMood = weatherMood(report)
        if (weatherMood == MascotMood.ALERT) return MascotMood.ALERT

        val domainMood = when (character) {
            MascotCharacter.PANDA -> sentimentMood(report.businessNews.headlines)
            MascotCharacter.OWL -> sentimentMood(report.news.headlines)
            MascotCharacter.CAT -> sentimentMood(report.politicsNews.headlines)
            MascotCharacter.BULL -> marketMood(report, favorsUp = true)
            MascotCharacter.BEAR -> marketMood(report, favorsUp = false)
            MascotCharacter.FOX -> cryptoMood(report)
            MascotCharacter.MAX -> weatherMood
        }
        // Worse-of-two: a mildly rough weather day still tempers an otherwise
        // upbeat domain mood, but never overrides a domain mood that's
        // already at least as concerned on its own.
        return maxOf(domainMood, weatherMood, compareBy { it.ordinal })
    }

    /**
     * Max's own briefing is the live police incident feed rather than the
     * digest report (see [AssistantReportBuilder]), so his mood on the
     * detail screen additionally factors in today's incident count - a busy
     * feed reads as more "alert" than an empty one, on top of the same
     * ambient weather severity every other analyst gets.
     */
    fun moodForMax(incidentCount: Int, report: DigestReport?): MascotMood {
        val weather = report?.let { weatherMood(it) } ?: MascotMood.CALM
        val incidents = when {
            incidentCount >= 8 -> MascotMood.ALERT
            incidentCount >= 3 -> MascotMood.CONCERNED
            incidentCount >= 1 -> MascotMood.CALM
            else -> MascotMood.HAPPY
        }
        return maxOf(weather, incidents, compareBy { it.ordinal })
    }

    private fun weatherMood(report: DigestReport): MascotMood {
        var score = 0
        if (report.weatherAlerts.alerts.isNotEmpty()) score += 2
        if (report.weatherAlerts.customAlerts.any { it.leadWarning }) score += 1
        val desc = report.weatherToday.description?.lowercase().orEmpty()
        if ("thunderstorm" in desc) score += 2
        if ("snow" in desc) score += 1
        val feelsLike = report.weatherToday.feelsLike ?: report.weatherToday.temp
        if (feelsLike != null && (feelsLike <= -5.0 || feelsLike >= 33.0)) score += 1
        return when {
            score >= 2 -> MascotMood.ALERT
            score == 1 -> MascotMood.CONCERNED
            else -> MascotMood.CALM
        }
    }

    private val positiveWords = listOf(
        "surge", "surges", "soar", "soars", "record", "wins", "win", "rally", "rallies",
        "boost", "boosts", "growth", "recovery", "recovers", "breakthrough", "deal",
        "agreement", "success", "gains", "gain", "rises", "rise", "jumps", "jump"
    )
    private val negativeWords = listOf(
        "crash", "crashes", "plunge", "plunges", "war", "attack", "attacks", "killed",
        "dead", "death", "crisis", "collapse", "recession", "warning", "warns", "threat",
        "fear", "fears", "disaster", "storm", "flood", "earthquake", "shooting", "conflict",
        "clash", "clashes", "layoffs", "fraud", "scandal", "outbreak"
    )

    /** Net keyword score over an analyst's own top few headlines - see the class doc for why this is intentionally simple. */
    private fun sentimentMood(headlines: List<NewsHeadline>): MascotMood {
        if (headlines.isEmpty()) return MascotMood.CALM
        var score = 0
        headlines.take(5).forEach { h ->
            val title = h.title.lowercase()
            if (positiveWords.any { it in title }) score++
            if (negativeWords.any { it in title }) score--
        }
        return when {
            score <= -2 -> MascotMood.ALERT
            score < 0 -> MascotMood.CONCERNED
            score == 0 -> MascotMood.CALM
            score == 1 -> MascotMood.HAPPY
            else -> MascotMood.EXCITED
        }
    }

    /**
     * [favorsUp] is true for Bully (bull persona thrives on gains) and false
     * for Beary (bear persona thrives on losses) - the same market move
     * reads as an opposite mood for each, which is the point: Bully looks
     * deflated on a red day, Beary looks energized by one.
     */
    private fun marketMood(report: DigestReport, favorsUp: Boolean): MascotMood {
        val topMoverChange = (if (favorsUp) report.stockGainers else report.stockLosers).firstOrNull()?.changePercent
        val trackedMoves = buildList {
            if (report.bitcoin.available) report.bitcoin.change24hPercent?.let { add(it) }
            if (report.currency.available) report.currency.change24hPercent?.let { add(it) }
            report.watchlist.forEach { entry -> if (entry.available) entry.change24hPercent?.let { add(it) } }
        }
        val signal = topMoverChange ?: trackedMoves.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val magnitude = abs(signal)
        val favorable = if (favorsUp) signal >= 0 else signal <= 0
        return when {
            magnitude < 1.0 -> MascotMood.CALM
            favorable && magnitude >= 4.0 -> MascotMood.EXCITED
            favorable -> MascotMood.HAPPY
            !favorable && magnitude >= 4.0 -> MascotMood.ALERT
            else -> MascotMood.CONCERNED
        }
    }

    private fun cryptoMood(report: DigestReport): MascotMood {
        if (!report.bitcoin.available) return MascotMood.CALM
        val change = report.bitcoin.change24hPercent ?: return MascotMood.CALM
        val magnitude = abs(change)
        return when {
            magnitude < 1.0 -> MascotMood.CALM
            change >= 5.0 -> MascotMood.EXCITED
            change > 0 -> MascotMood.HAPPY
            change <= -5.0 -> MascotMood.ALERT
            else -> MascotMood.CONCERNED
        }
    }
}
