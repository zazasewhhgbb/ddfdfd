package com.morningdigest.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.prefs.DashboardCards
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Preferences key holding this widget instance's chosen card ids, comma-joined (e.g. "weather,markets"). Written by [WidgetConfigActivity]. */
val WIDGET_SELECTED_CARDS_KEY = stringPreferencesKey("widget_selected_cards")

class MorningDigestWidget : GlanceAppWidget() {

    // Per-widget-instance state (which cards this particular home screen
    // widget shows), set from the configuration screen shown when the
    // widget is first added (and re-openable via "Edit" on API 31+, since
    // widget_info.xml declares android:widgetFeatures="reconfigurable").
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as MorningDigestApp).container
        val report = container.digestRepository.getLatestReport()
        val settings = container.settingsRepository.currentSettings()

        provideContent {
            val prefs = currentState<Preferences>()
            val selectedCards = prefs[WIDGET_SELECTED_CARDS_KEY]
                ?.split(",")
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }
                ?: DashboardCards.DEFAULT_ORDER // Not configured yet (or an old widget from before this feature) - full digest, same as before.

            Column(
                modifier = androidx.glance.GlanceModifier
                    .fillMaxSize()
                    .background(Color(0xFF1C1B2E))
                    .padding(12.dp)
            ) {
                // Header: city name, always shown regardless of card selection.
                Text(
                    "\uD83C\uDF05 ${settings.city}",
                    style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                )

                DashboardCards.DEFAULT_ORDER.filter { it in selectedCards }.forEach { cardKey ->
                    Spacer(modifier = androidx.glance.GlanceModifier.height(6.dp))
                    when (cardKey) {
                        DashboardCards.WEATHER -> WeatherSection(report)
                        DashboardCards.MARKETS -> MarketsSection(report)
                        DashboardCards.TOMORROW -> TomorrowSection(report)
                        DashboardCards.FACT -> FactSection(report)
                    }
                }

                report?.let {
                    Spacer(modifier = androidx.glance.GlanceModifier.height(6.dp))
                    val lastUpdate = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestampMillis))
                    Text(
                        "Updated $lastUpdate",
                        style = TextStyle(color = ColorProvider(Color(0xFF8A85A8)), fontSize = 10.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherSection(report: DigestReport?) {
    if (report != null && report.weatherToday.available) {
        val w = report.weatherToday
        Row(modifier = androidx.glance.GlanceModifier.fillMaxWidth()) {
            Text(
                "${w.temp?.roundToInt() ?: "—"}°C",
                style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold, fontSize = 24.sp)
            )
            Spacer(modifier = androidx.glance.GlanceModifier.padding(horizontal = 6.dp))
            Text(
                w.description?.replaceFirstChar { it.uppercase() } ?: "",
                style = TextStyle(color = ColorProvider(Color(0xFFB8B4D6)), fontSize = 12.sp)
            )
        }
    } else {
        Text("—°C", style = TextStyle(color = ColorProvider(Color.White)))
    }
}

@Composable
private fun TomorrowSection(report: DigestReport?) {
    val tomorrow = report?.weatherTomorrow
    Text(
        if (tomorrow?.available == true)
            "Tomorrow: ${tomorrow.avgTemp?.roundToInt() ?: "—"}° avg \u00B7 \u2614 ${tomorrow.rainChancePercent ?: 0}%"
        else "Tomorrow: —",
        style = TextStyle(color = ColorProvider(Color(0xFFB8B4D6)), fontSize = 11.sp)
    )
}

@Composable
private fun MarketsSection(report: DigestReport?) {
    Column {
        // Bitcoin, with 24h change
        val bitcoin = report?.bitcoin
        Row(modifier = androidx.glance.GlanceModifier.fillMaxWidth()) {
            Text(
                if (bitcoin?.available == true) "\u20BF \u20AC${bitcoin.eur?.toInt()}" else "\u20BF —",
                style = TextStyle(color = ColorProvider(Color(0xFFFFC44D)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            )
            if (bitcoin?.available == true && bitcoin.change24hPercent != null) {
                Spacer(modifier = androidx.glance.GlanceModifier.padding(horizontal = 6.dp))
                val change = bitcoin.change24hPercent
                Text(
                    "${if (change >= 0) "▲" else "▼"} ${"%.2f".format(abs(change))}%",
                    style = TextStyle(
                        color = ColorProvider(if (change >= 0) Color(0xFF4ADE80) else Color(0xFFFF6B6B)),
                        fontSize = 12.sp
                    )
                )
            }
        }

        // Configured currency pair, with 24h change
        val currency = report?.currency
        Row(modifier = androidx.glance.GlanceModifier.fillMaxWidth()) {
            Text(
                if (currency?.available == true) "${currency.baseCurrency}\u2192${currency.targetCurrency} ${String.format(Locale.US, "%.2f", currency.rate)}" else "Currency —",
                style = TextStyle(color = ColorProvider(Color(0xFFB8B4D6)), fontSize = 11.sp)
            )
            if (currency?.available == true && currency.change24hPercent != null) {
                Spacer(modifier = androidx.glance.GlanceModifier.padding(horizontal = 6.dp))
                val change = currency.change24hPercent
                Text(
                    "${if (change >= 0) "▲" else "▼"} ${"%.2f".format(abs(change))}%",
                    style = TextStyle(
                        color = ColorProvider(if (change >= 0) Color(0xFF4ADE80) else Color(0xFFFF6B6B)),
                        fontSize = 11.sp
                    )
                )
            }
        }
    }
}

@Composable
private fun FactSection(report: DigestReport?) {
    val fact = report?.dailyFact
    if (fact != null && fact.text.isNotBlank()) {
        Text(
            "\uD83D\uDCA1 ${fact.text}",
            maxLines = 3,
            style = TextStyle(color = ColorProvider(Color(0xFFE4E1F5)), fontSize = 11.sp)
        )
    } else {
        Text("\uD83D\uDCA1 —", style = TextStyle(color = ColorProvider(Color(0xFFE4E1F5)), fontSize = 11.sp))
    }
}

class MorningDigestWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MorningDigestWidget()
}

/** Writes [cardIds] as this widget instance's card selection and immediately re-renders it. Called from [WidgetConfigActivity]'s Save button. */
suspend fun saveWidgetCardSelection(context: Context, glanceId: GlanceId, cardIds: List<String>) {
    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
        prefs.toMutablePreferences().apply {
            this[WIDGET_SELECTED_CARDS_KEY] = cardIds.joinToString(",")
        }
    }
    MorningDigestWidget().update(context, glanceId)
}
