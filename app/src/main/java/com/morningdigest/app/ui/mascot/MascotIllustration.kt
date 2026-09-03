package com.morningdigest.app.ui.mascot

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.morningdigest.app.data.facts.MascotMood
import com.morningdigest.app.data.prefs.MascotCharacter

/**
 * The assistant character's portrait - a square source photo (see
 * res/drawable-nodpi/mascot_*.png), masked into a circle so it reads as a
 * clean avatar regardless of what's around the edges of the source image.
 *
 * [mood] is optional so every existing call site (Settings' avatar picker,
 * for one, which has no report to derive a mood from) keeps its plain
 * neutral ring with zero changes. When a mood is supplied, the ring recolors
 * to match it and a subtle desaturation kicks in for the two "not great"
 * moods - a cheap, no-extra-art way to make the same static photo read as
 * more subdued without needing a second drawable per character.
 */
@Composable
fun MascotIllustration(
    character: MascotCharacter,
    modifier: Modifier = Modifier.size(120.dp),
    mood: MascotMood? = null
) {
    val ringColor = mood?.let { ringColorFor(it) } ?: MaterialTheme.colorScheme.surface
    val colorFilter = mood?.let { desaturationFor(it) }
    Image(
        painter = painterResource(id = character.drawableRes),
        contentDescription = moodDescription(character, mood),
        contentScale = ContentScale.Crop,
        colorFilter = colorFilter,
        modifier = modifier
            .clip(CircleShape)
            .border(2.dp, ringColor, CircleShape)
    )
}

private fun moodDescription(character: MascotCharacter, mood: MascotMood?): String {
    val base = "${character.displayName}, ${character.role}"
    return if (mood == null) base else "$base, looking ${mood.name.lowercase()}"
}

/** Ring color per mood - warm gold for a great day, cooling through neutral to red as things get more urgent. */
private fun ringColorFor(mood: MascotMood): Color = when (mood) {
    MascotMood.EXCITED -> Color(0xFFFFC107)
    MascotMood.HAPPY -> Color(0xFF66BB6A)
    MascotMood.CALM -> Color(0xFF90A4AE)
    MascotMood.CONCERNED -> Color(0xFFFF9800)
    MascotMood.ALERT -> Color(0xFFE53935)
}

/** Light desaturation for the down moods only - the portrait itself stays recognizable, just a touch flatter. */
private fun desaturationFor(mood: MascotMood): ColorFilter? {
    val saturation = when (mood) {
        MascotMood.CONCERNED -> 0.75f
        MascotMood.ALERT -> 0.55f
        else -> return null
    }
    return ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
}
