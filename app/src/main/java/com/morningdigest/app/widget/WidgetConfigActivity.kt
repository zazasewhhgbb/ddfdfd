package com.morningdigest.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.morningdigest.app.data.prefs.DashboardCards
import com.morningdigest.app.ui.theme.MorningDigestTheme
import kotlinx.coroutines.launch

/**
 * Shown automatically by Android the moment a [MorningDigestWidget] is
 * dropped on the home screen (registered as its android:configure target in
 * widget_info.xml), and re-openable afterwards via the launcher's widget
 * "Edit" action since the widget declares android:widgetFeatures="reconfigurable".
 * Lets the user pick which dashboard cards (Settings > Dashboard Layout's
 * [DashboardCards]) that specific widget instance shows - e.g. a
 * weather-only widget on one home screen and a fuller one elsewhere,
 * without the two affecting each other.
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Standard app widget config contract: default to CANCELED so the
        // widget isn't placed at all if the user backs out without saving.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()
            MorningDigestTheme(darkTheme = darkTheme) {
                ConfigScreen(
                    onSave = { selectedCards -> saveAndFinish(selectedCards) }
                )
            }
        }
    }

    private fun saveAndFinish(selectedCards: List<String>) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity)
                .getGlanceIdBy(appWidgetId)
            saveWidgetCardSelection(this@WidgetConfigActivity, glanceId, selectedCards)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigScreen(onSave: (List<String>) -> Unit) {
    // Defaults to the full digest (same as before this feature existed) -
    // the user thins it out from there, e.g. down to just Weather.
    var selected by remember { mutableStateOf(DashboardCards.DEFAULT_ORDER.toSet()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Configure Widget") }) }
    ) { padding ->
        Surface(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    "Pick which cards this widget shows (2-3 fits best - pick just Weather for a compact widget, or all four for the full digest).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                DashboardCards.DEFAULT_ORDER.forEach { cardKey ->
                    val label = DashboardCards.LABELS[cardKey] ?: cardKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = cardKey in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + cardKey else selected - cardKey
                            }
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { selected = setOf(DashboardCards.WEATHER) }) {
                        Text("Weather only")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { selected = DashboardCards.DEFAULT_ORDER.toSet() }) {
                        Text("Full digest")
                    }
                }

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { onSave(DashboardCards.DEFAULT_ORDER.filter { it in selected }) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save")
                }
            }
        }
    }
}
