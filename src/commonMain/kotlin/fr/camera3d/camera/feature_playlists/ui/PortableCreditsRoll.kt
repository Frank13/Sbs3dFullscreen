package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable part of the playlist credits (Playlist.credits): the roll duration rule, the roll clock and
// the drawing of the credits rolling from the bottom to the top of their bounding box, fading in/out
// over the bottom/top 10% of the box. Each platform layers it over its slides (stereo halves, beamer
// half-SBS squeeze, anaglyph). Must stay free of android.* imports. Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Credits roll speed when the playlist scroll speed limit is 0 ("no limit"), in % of the slide height per second */
const val DEFAULT_CREDITS_SPEED_PERCENT = 5f

/** Fraction of the credits box height, at its bottom and top, over which the credits fade in and out */
private const val CREDITS_FADE_FRACTION = 0.1f

/** Credits roll speed in % of the slide height per second: the playlist max scroll speed */
fun Playlist.creditsSpeedPercent(): Float = if (scrollSpeedLimitPercent > 0f) scrollSpeedLimitPercent else DEFAULT_CREDITS_SPEED_PERCENT

/**
 * Duration of the credits roll, from the credits top entering the bottom of their box until the credits bottom
 * completely exits through its top, at [speedPercent] % of [slideHeight] per second; 0 if it can't be computed.
 */
fun creditsRollDurationMs(boxHeight: Float, textHeight: Float, slideHeight: Float, speedPercent: Float): Long {
    if (slideHeight <= 0f || speedPercent <= 0f) return 0
    return ((boxHeight + textHeight) / (slideHeight * speedPercent / 100f) * 1000f).roundToLong()
}

/**
 * Elapsed time of the credits roll in ms, advancing with the frame clock except while [isPaused].
 * Remembered once and passed to every view of the credits (e.g. both eyes) so they stay in sync.
 */
@Composable
fun rememberCreditsRollElapsedMs(isPaused: Boolean): State<Long> {
    val elapsedMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPaused) {
        if (isPaused) return@LaunchedEffect
        var lastFrameMs = withFrameMillis { it }
        while (true) {
            withFrameMillis { frameMs ->
                elapsedMs.longValue += frameMs - lastFrameMs
                lastFrameMs = frameMs
            }
        }
    }
    return elapsedMs
}

/**
 * Stereo credits roll: the credits duplicated on both halves of the screen, each offset by the depth
 * shift [zPercent] (% of the eye view width, same convention as the title slide).
 * @param eyeAspectRatio : see ComposablePortableTitleSlide
 * @param onRemainingMs : see ComposableCreditsRollView
 */
@Composable
fun ComposableCreditsRollStereo(credits: String, style: TextStyleConfig, zPercent: Float, speedPercent: Float, isPaused: Boolean,
                                eyeAspectRatio: Float? = null, onRemainingMs: (Long) -> Unit = {}) {
    val elapsedMs = rememberCreditsRollElapsedMs(isPaused)
    Row(Modifier.fillMaxSize()) {
        listOf(-1f, 1f).forEach { shiftSign -> // left, right
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                val eyeModifier = if (eyeAspectRatio != null) Modifier.aspectRatio(eyeAspectRatio) else Modifier.fillMaxSize()
                BoxWithConstraints(eyeModifier) {
                    ComposableCreditsRollView(credits, style, speedPercent, elapsedMs, isPaused,
                        extraShiftDp = maxWidth * (zPercent / 100f) * shiftSign / 2, onRemainingMs = onRemainingMs)
                }
            }
        }
    }
}

/**
 * One view of the rolling credits: they enter through the bottom of their bounding box ([style] top/left/right/bottom %
 * of this view) and roll up until they have completely exited through its top, fading in/out over the bottom/top 10%
 * of the box. Font size and speed are % of this view's height.
 * @param elapsedMs : see rememberCreditsRollElapsedMs, only read while drawing
 * @param extraShiftDp : horizontal stereo depth shift of this eye, see ComposableStyledPositionedText
 * @param anaglyphShiftPercent : when set, the credits are drawn as a red/cyan anaglyph with this depth shift
 * (same convention as ComposableAnaglyphText's shiftPercent) instead of plain text
 * @param onRemainingMs : called with the remaining roll duration once the credits are measured, and on each resume,
 * so that the slideshow can wait for the end of the roll
 */
@Composable
fun ComposableCreditsRollView(credits: String, style: TextStyleConfig, speedPercent: Float, elapsedMs: State<Long>, isPaused: Boolean,
                              extraShiftDp: Dp = 0.dp, anaglyphShiftPercent: Float? = null, onRemainingMs: (Long) -> Unit = {}) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val currentOnRemainingMs by rememberUpdatedState(onRemainingMs)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val slideWidth = constraints.maxWidth.toFloat()
        val slideHeight = constraints.maxHeight.toFloat()
        val boxLeft = slideWidth * style.leftPercent / 100f + with(density) { extraShiftDp.toPx() }
        val boxTop = slideHeight * style.topPercent / 100f
        val boxWidth = (slideWidth * (1f - (style.leftPercent + style.rightPercent) / 100f)).coerceAtLeast(1f)
        val boxHeight = (slideHeight * (1f - (style.topPercent + style.bottomPercent) / 100f)).coerceAtLeast(1f)
        // font size is a % of slide height, see ComposableStyledPositionedText
        val textStyle = style.toComposeTextStyle(with(density) { (slideHeight * style.fontSizePercent / 100f).toSp() })
        val layout = remember(credits, textStyle, boxWidth) {
            textMeasurer.measure(credits, textStyle, constraints = Constraints.fixedWidth(boxWidth.roundToInt()))
        }
        val durationMs = creditsRollDurationMs(boxHeight, layout.size.height.toFloat(), slideHeight, speedPercent)
        LaunchedEffect(durationMs, isPaused) {
            if (!isPaused && slideHeight > 0f) currentOnRemainingMs((durationMs - elapsedMs.value).coerceAtLeast(0))
        }
        val pxPerMs = slideHeight * speedPercent / 100f / 1000f
        val backgroundColor = parseHexColor(style.backgroundColorHex, Color.Black)
            .copy(alpha = (style.backgroundOpacityPercent / 100f).coerceIn(0f, 1f))
        val textColor = parseHexColor(style.colorHex, Color.White)
        val boxTopLeft = Offset(boxLeft, boxTop)
        val boxSize = Size(boxWidth, boxHeight)
        // the background covers the whole box, fade zones included: drawn on its own so that the fade mask never applies to it
        if (style.backgroundOpacityPercent > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(backgroundColor, topLeft = boxTopLeft, size = boxSize)
            }
        }
        // offscreen so that the fade mask (DstIn) only applies to the credits text, not to what is behind it
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            clipRect(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight) {
                val textTopLeft = Offset(boxLeft, boxTop + boxHeight - elapsedMs.value * pxPerMs)
                if (anaglyphShiftPercent == null) {
                    drawText(layout, topLeft = textTopLeft)
                } else {
                    val shift = size.width / 2f * anaglyphShiftPercent
                    drawText(layout, color = Color(red = textColor.red, green = 0f, blue = 0f, alpha = textColor.alpha),
                        topLeft = textTopLeft + Offset(-shift / 2f, 0f))
                    drawText(layout, color = Color(red = 0f, green = textColor.green, blue = textColor.blue, alpha = textColor.alpha),
                        topLeft = textTopLeft + Offset(shift / 2f, 0f), blendMode = BlendMode.Lighten)
                }
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent, CREDITS_FADE_FRACTION to Color.Black,
                        1f - CREDITS_FADE_FRACTION to Color.Black, 1f to Color.Transparent,
                        startY = boxTop, endY = boxTop + boxHeight,
                    ),
                    topLeft = boxTopLeft, size = boxSize, blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}
