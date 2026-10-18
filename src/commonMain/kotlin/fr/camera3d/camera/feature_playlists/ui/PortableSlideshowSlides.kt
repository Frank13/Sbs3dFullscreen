package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable versions of the stereo title/end slides shown on the beamer's secondary display
// (BeamerPresentation.kt's ComposableBeamerTitleSlide / SlideshowFragment.kt's
// ComposableBeamerEndSlide) - the "external screen" a beamer/projector shows, which sbs3Dfullscreen's
// borderless fullscreen window is the desktop equivalent of. Only the pure stereo visuals live here;
// each platform/mode layers its own interaction UI around them (Play button and buttons-on-tap on
// Android, a keyboard hint on desktop), same convention as PlaylistFieldsComposables.kt's split
// between portable visuals and caller-supplied strings/callbacks. Used directly by SlideshowFragment.kt's
// SIDE_BY_SIDE view mode; BeamerPresentation.kt's two-screen beamer output keeps its own
// implementation (extra logo-layer/left-right-inversion support that doesn't apply to desktop).
// Sync via tools/sync-from-android.ps1.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig

/**
 * The stereo title slide: name/subtitle duplicated on both halves of the screen, each offset by
 * a "depth shift" (stereo altitude) percentage of half the screen width, so it reads correctly in
 * 3D. Ported from BeamerPresentation.kt's ComposableBeamerTitleSlide, dropping its
 * beamer-only logo-layer/left-right-inversion support (stays Android-side, layered around this).
 * [eyeAspectRatio]: when set (e.g. 16f / 9f for a full-SBS screen), each eye view is letterboxed to
 * that ratio inside its half; null fills the whole half (half-SBS, stretched 2x by the display).
 */
@Composable
fun ComposablePortableTitleSlide(
    title: String,
    subtitle: String,
    titleShiftPercent: Float,
    titleStyle: TextStyleConfig = TextStyleConfig.TITLE_DEFAULT,
    subtitleShiftPercent: Float = 0f,
    subtitleStyle: TextStyleConfig = TextStyleConfig.SUBTITLE_DEFAULT,
    eyeAspectRatio: Float? = null,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) { // left
            ComposablePortableTitleEye(eyeAspectRatio, -1f, title, titleStyle, titleShiftPercent, subtitle, subtitleStyle, subtitleShiftPercent)
        }
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) { // right
            ComposablePortableTitleEye(eyeAspectRatio, 1f, title, titleStyle, titleShiftPercent, subtitle, subtitleStyle, subtitleShiftPercent)
        }
    }
}

/**
 * One eye's view of the title slide: fills its half, or with [eyeAspectRatio] the largest box of
 * that ratio centered in it (e.g. 16:9 for full-SBS so the layout matches the style editor's
 * preview). Depth shifts are a % of this eye view's width; [shiftSign] is -1 left, +1 right.
 */
@Composable
private fun ComposablePortableTitleEye(
    eyeAspectRatio: Float?,
    shiftSign: Float,
    title: String,
    titleStyle: TextStyleConfig,
    titleShiftPercent: Float,
    subtitle: String,
    subtitleStyle: TextStyleConfig,
    subtitleShiftPercent: Float,
) {
    val eyeModifier = if (eyeAspectRatio != null) Modifier.aspectRatio(eyeAspectRatio) else Modifier.fillMaxSize()
    BoxWithConstraints(eyeModifier) {
        val titleShift = maxWidth * titleShiftPercent * shiftSign / 2
        val subtitleShift = maxWidth * subtitleShiftPercent * shiftSign / 2
        ComposablePortableTitleHalf(title, titleStyle, titleShift, subtitle, subtitleStyle, subtitleShift)
    }
}

@Composable
private fun ComposablePortableTitleHalf(
    title: String,
    titleStyle: TextStyleConfig,
    titleShiftDp: Dp,
    subtitle: String,
    subtitleStyle: TextStyleConfig,
    subtitleShiftDp: Dp,
) {
    ComposableStyledPositionedText(titleStyle, titleShiftDp) { textStyle, modifier ->
        Text(text = title, style = textStyle, modifier = modifier)
    }
    if (subtitle.isNotEmpty()) {
        ComposableStyledPositionedText(subtitleStyle, subtitleShiftDp) { textStyle, modifier ->
            Text(text = subtitle, style = textStyle, modifier = modifier)
        }
    }
}

/**
 * The stereo end slide: "the end" label growing and shifting in depth over 5s, split left/right.
 * Ported from SlideshowFragment.kt's ComposableBeamerEndSlide (the beamer secondary display's end
 * slide), dropping only its left-right-inversion support (beamer-only, stays Android-side).
 * Uses a graphicsLayer transform on a fixed-size Text (instead of animating fontSize directly) so
 * the growth doesn't re-measure the Text every frame - see the original's comment for why that
 * matters once projected large on a beamer screen.
 * [labelHeightFraction]: when set, the label's base font size is that fraction of the available
 * height (the grow animation still runs 80%..120% around it); null keeps the fixed 50sp.
 */
@Composable
fun ComposablePortableEndSlide(endLabel: String, labelHeightFraction: Float? = null) {
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        animationProgress.animateTo(1f, animationSpec = tween(durationMillis = 5000, easing = LinearEasing))
    }
    val progress = animationProgress.value
    val zShiftPercent = lerp(0.015f, -0.015f, progress)
    val scale = lerp(0.8f, 1.2f, progress) // 40sp..60sp around the 50sp base size below
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shiftPx = with(density) { (halfWidth * zShiftPercent / 2f).toPx() }
        val labelFontSize = if (labelHeightFraction != null) with(density) { (maxHeight * labelHeightFraction).toSp() } else 50f.sp

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) { // left
                Text(
                    text = endLabel,
                    style = TextStyle(fontSize = labelFontSize, color = Color.White),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = -shiftPx
                    },
                )
            }
            Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) { // right
                Text(
                    text = endLabel,
                    style = TextStyle(fontSize = labelFontSize, color = Color.White),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = shiftPx
                    },
                )
            }
        }
    }
}
