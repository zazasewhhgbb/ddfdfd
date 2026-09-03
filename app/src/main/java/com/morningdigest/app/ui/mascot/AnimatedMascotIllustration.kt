package com.morningdigest.app.ui.mascot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morningdigest.app.data.facts.MascotMood
import com.morningdigest.app.data.prefs.MascotCharacter
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos

/**
 * Wraps [MascotIllustration] with a small always-on animation so the
 * Assistants tab feels alive instead of a static list of photos, and gives
 * each character its own tiny animated badge tied to their role:
 * - Bully (bull) gets a pulsing 👍, Beary (bear) a pulsing 👎
 * - Scoop (world news) gets a small spinning globe 🌐
 * - Satoshi (crypto) gets a slow spinning ₿
 * - Panda (business) gets a wiggling necktie 👔
 * - Anja (politics) gets a pulsing 🏛️
 * - Max (police) gets a spinning police shield 🛡️
 *
 * [mood] (see [MascotMoodEngine][com.morningdigest.app.data.facts.MascotMoodEngine])
 * layers real-world reactivity on top of that role animation rather than
 * replacing it: it recolors [MascotIllustration]'s ring, adds a small
 * opposite-corner expression badge, and scales how fast/far the float and
 * wobble move - a jittery, wide wobble for [MascotMood.ALERT], a slow,
 * barely-there droop for [MascotMood.CONCERNED], a bouncier one for
 * [MascotMood.EXCITED] - so the portrait itself reads as reacting to
 * conditions, not just decoratively animating. Defaults to [MascotMood.CALM]
 * so call sites with no report handy (e.g. the Settings avatar picker via
 * plain [MascotIllustration]) are unaffected.
 *
 * Deliberately NOT built on [androidx.compose.animation.core.rememberInfiniteTransition]:
 * that API intentionally freezes to a static frame when the device's
 * animator duration scale is 0 - which happens whenever the "Remove
 * animations" accessibility setting is on, an option that's more commonly
 * surfaced on Android 14. That's almost certainly why these went static on
 * that phone. Driving the values by hand with a plain per-frame loop below
 * ignores that setting so the mascots always animate, since this motion is
 * purely decorative rather than something that could cause discomfort.
 */
@Composable
fun AnimatedMascotIllustration(
    character: MascotCharacter,
    modifier: Modifier = Modifier.size(120.dp),
    size: Dp = 120.dp,
    seedIndex: Int = 0,
    mood: MascotMood = MascotMood.CALM
) {
    var floatPx by remember { mutableFloatStateOf(0f) }
    var wobbleDeg by remember { mutableFloatStateOf(0f) }
    var badgeScale by remember { mutableFloatStateOf(1f) }
    var moodBadgeScale by remember { mutableFloatStateOf(1f) }
    var fastSpinDeg by remember { mutableFloatStateOf(0f) }
    var slowSpinDeg by remember { mutableFloatStateOf(0f) }
    var tieWiggleDeg by remember { mutableFloatStateOf(0f) }
    var shieldSpinDeg by remember { mutableFloatStateOf(0f) }

    // Speed: <1 = faster/twitchier, >1 = slower/heavier. Amplitude: how far
    // the float/wobble swing. ALERT is fast AND wide (urgent), CONCERNED is
    // slow AND narrow (deflated), EXCITED is fast and wide but bouncy rather
    // than jittery (handled by the badge below), CALM is the original feel.
    val speedMultiplier = when (mood) {
        MascotMood.ALERT -> 0.5f
        MascotMood.EXCITED -> 0.7f
        MascotMood.HAPPY -> 0.9f
        MascotMood.CALM -> 1f
        MascotMood.CONCERNED -> 1.4f
    }
    val amplitudeMultiplier = when (mood) {
        MascotMood.ALERT -> 1.8f
        MascotMood.EXCITED -> 1.5f
        MascotMood.HAPPY -> 1.15f
        MascotMood.CALM -> 1f
        MascotMood.CONCERNED -> 0.55f
    }

    LaunchedEffect(character, seedIndex, speedMultiplier) {
        val floatPeriodMs = (1500f + seedIndex * 130f) * speedMultiplier
        val wobblePeriodMs = (2100f + seedIndex * 170f) * speedMultiplier
        val badgePeriodMs = 750f
        val moodBadgePeriodMs = 900f
        val fastSpinPeriodMs = 2600f + seedIndex * 90f   // globe
        val slowSpinPeriodMs = 4800f + seedIndex * 140f  // bitcoin
        val tiePeriodMs = 1100f
        val shieldSpinPeriodMs = 3400f + seedIndex * 110f // police shield

        var startNanos = -1L
        while (isActive) {
            withFrameNanos { nowNanos ->
                if (startNanos < 0L) startNanos = nowNanos
                val elapsedMs = (nowNanos - startNanos) / 1_000_000f

                floatPx = oscillate(elapsedMs, floatPeriodMs, -6f * amplitudeMultiplier, 6f * amplitudeMultiplier)
                wobbleDeg = oscillate(elapsedMs, wobblePeriodMs, -5f * amplitudeMultiplier, 5f * amplitudeMultiplier)
                badgeScale = oscillate(elapsedMs, badgePeriodMs, 0.85f, 1.2f)
                moodBadgeScale = oscillate(elapsedMs, moodBadgePeriodMs, 0.9f, 1.15f)
                tieWiggleDeg = oscillate(elapsedMs, tiePeriodMs, -10f, 10f)
                fastSpinDeg = (elapsedMs % fastSpinPeriodMs) / fastSpinPeriodMs * 360f
                slowSpinDeg = (elapsedMs % slowSpinPeriodMs) / slowSpinPeriodMs * 360f
                shieldSpinDeg = (elapsedMs % shieldSpinPeriodMs) / shieldSpinPeriodMs * 360f
            }
        }
    }

    Box(contentAlignment = Alignment.Center) {
        MascotIllustration(
            character,
            mood = mood,
            modifier = modifier.graphicsLayer {
                translationY = floatPx
                rotationZ = wobbleDeg
            }
        )

        when (character) {
            MascotCharacter.BULL -> MascotBadge("👍", size, floatPx, Alignment.BottomEnd) { scaleX = badgeScale; scaleY = badgeScale }
            MascotCharacter.BEAR -> MascotBadge("👎", size, floatPx, Alignment.BottomEnd) { scaleX = badgeScale; scaleY = badgeScale }
            MascotCharacter.OWL -> MascotBadge("🌐", size, floatPx, Alignment.BottomEnd) { rotationZ = fastSpinDeg }
            MascotCharacter.FOX -> MascotBadge("₿", size, floatPx, Alignment.BottomEnd) { rotationZ = slowSpinDeg }
            MascotCharacter.PANDA -> MascotBadge("👔", size, floatPx, Alignment.BottomEnd) { rotationZ = tieWiggleDeg }
            MascotCharacter.CAT -> MascotBadge("🏛️", size, floatPx, Alignment.BottomEnd) { scaleX = badgeScale; scaleY = badgeScale }
            MascotCharacter.MAX -> MascotBadge("🛡️", size, floatPx, Alignment.BottomEnd) { rotationZ = shieldSpinDeg }
        }

        // Mood badge sits in the opposite corner from the role badge above,
        // so both stay legible even at small sizes - this one reacts to
        // real conditions (weather severity / news sentiment / price moves),
        // the other is a fixed role identifier.
        MascotBadge(mood.emoji, size, floatPx, Alignment.TopStart) { scaleX = moodBadgeScale; scaleY = moodBadgeScale }
    }
}

/** Smooth, seamless min<->max oscillation with no snap at the loop point (unlike a sawtooth/triangle wave). */
private fun oscillate(elapsedMs: Float, periodMs: Float, min: Float, max: Float): Float {
    val phase = (elapsedMs % periodMs) / periodMs
    val eased = (1f - cos(2f * PI.toFloat() * phase)) / 2f
    return min + (max - min) * eased
}

@Composable
private fun BoxScope.MascotBadge(
    emoji: String,
    size: Dp,
    floatPx: Float,
    alignment: Alignment = Alignment.BottomEnd,
    transform: GraphicsLayerScope.() -> Unit
) {
    Box(
        Modifier
            .align(alignment)
            .graphicsLayer {
                translationY = floatPx
                transform()
            }
    ) {
        Text(emoji, fontSize = (size.value * 0.34f).sp, style = MaterialTheme.typography.bodyLarge)
    }
}
