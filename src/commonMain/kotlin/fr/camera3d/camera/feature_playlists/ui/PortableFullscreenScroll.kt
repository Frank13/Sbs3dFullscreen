package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable part of the "force fullscreen by scrolling" slide (PlaylistItem.fullscreenScroll): the
// slide duration rule, the scrolling animation and the fill-and-scroll drawing of already decoded
// images. Decoding the photo, splitting it into left/right and computing an anaglyph stay
// platform-specific (FullscreenScrollSlide.kt on Android), each platform then passing ImageBitmaps here.
// Must stay free of android.* imports. Sync via tools/sync-from-android.ps1.

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import fr.camera3d.camera.feature_playlists.domain.FullscreenScroll
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Returns the scrolling duration of a "force fullscreen by scrolling" photo (its slide duration, before any
 * extension due to [Playlist.scrollSpeedLimitPercent]),
 * or null when the option does not apply: option off, video item, or manual playlist (no slide duration).
 */
fun Playlist.fullscreenScrollDurationMs(photo: PlaylistItem?): Long? {
    if (photo == null || photo.isVideo || !isAutomated || photo.fullscreenScroll == FullscreenScroll.NONE) return null
    val durationS = if (photo.durationS > 0) photo.durationS.toLong() else defaultDurationS
    return durationS * 1000
}

/**
 * Minimum scrolling duration of an image filling (cover) a box, so that it does not scroll faster than
 * [speedLimitPercent] % of the box length (along the scrolling axis) per second; 0 when there is no limit.
 * Sizes are in "real aspect" coordinates (see ComposableFullscreenScrollImage).
 */
fun fullscreenScrollMinDurationMs(imageWidth: Float, imageHeight: Float, boxWidth: Float, boxHeight: Float, speedLimitPercent: Float): Long {
    if (speedLimitPercent <= 0f || imageWidth <= 0f || imageHeight <= 0f || boxWidth <= 0f || boxHeight <= 0f) return 0
    val scale = max(boxWidth / imageWidth, boxHeight / imageHeight)
    // the image overflows on one axis only, the other term is ~0
    val scrolledBoxLengths = (imageWidth * scale - boxWidth) / boxWidth + (imageHeight * scale - boxHeight) / boxHeight
    return (scrolledBoxLengths / (speedLimitPercent / 100f) * 1000f).roundToLong()
}

/**
 * Stereo pair of a "force fullscreen by scrolling" photo: each eye image fills its half screen
 * (cropped on one axis) and scrolls in [PlaylistItem.fullscreenScroll] direction during [durationMs],
 * extended if needed so that it does not scroll faster than [speedLimitPercent] (see fullscreenScrollMinDurationMs).
 * Nothing is drawn while [left]/[right] are null (still loading); the scrolling starts once they are
 * given, restarts for new images, and freezes while [isPaused].
 * @param displaySqueezeX : 0.5f for a half-width SBS display (beamer/3D TV stretching each half 2x), 1f otherwise
 * @param onDurationMs : called with the actual scrolling duration once the images are loaded,
 * so that the slideshow can extend the slide duration accordingly
 */
@Composable
fun ComposableFullscreenScrollStereoImages(left: ImageBitmap?, right: ImageBitmap?, photo: PlaylistItem, durationMs: Long,
                                           isPaused: Boolean, displaySqueezeX: Float = 1f, leftRightInverted: Boolean = false,
                                           speedLimitPercent: Float = 0f, onDurationMs: (Long) -> Unit = {}) {
    // a half-width SBS photo has squeezed halves: stretch them back to their real aspect ratio
    val sourceStretchX = if (photo.isHalfWidth) 2f else 1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // each eye's box is half of this one
        val minDurationMs = if (left == null) 0L else fullscreenScrollMinDurationMs(left.width * sourceStretchX, left.height.toFloat(),
            constraints.maxWidth / 2f / displaySqueezeX, constraints.maxHeight.toFloat(), speedLimitPercent)
        val progress = rememberFullscreenScrollProgress(left, isLoaded = left != null && right != null, isPaused,
            max(durationMs, minDurationMs), onDurationMs)
        if (left == null || right == null) return@BoxWithConstraints
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                ComposableFullscreenScrollImage(if (leftRightInverted) right else left, photo.fullscreenScroll, progress.value, sourceStretchX, displaySqueezeX)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                ComposableFullscreenScrollImage(if (leftRightInverted) left else right, photo.fullscreenScroll, progress.value, sourceStretchX, displaySqueezeX)
            }
        }
    }
}

/**
 * Single image (e.g. an anaglyph) of a "force fullscreen by scrolling" photo: it fills the whole box
 * (cropped on one axis) and scrolls in [PlaylistItem.fullscreenScroll] direction during [durationMs],
 * extended if needed so that it does not scroll faster than [speedLimitPercent] (see fullscreenScrollMinDurationMs).
 * Nothing is drawn while [image] is null (still loading); the scrolling starts once it is given,
 * restarts for a new image, and freezes while [isPaused].
 * @param image : one eye's size image, i.e. built from a single half of the SBS photo
 * @param onDurationMs : see ComposableFullscreenScrollStereoImages
 */
@Composable
fun ComposableFullscreenScrollMonoImage(image: ImageBitmap?, photo: PlaylistItem, durationMs: Long, isPaused: Boolean,
                                        speedLimitPercent: Float = 0f, onDurationMs: (Long) -> Unit = {}) {
    // a half-width SBS photo has squeezed halves: stretch them back to their real aspect ratio
    val sourceStretchX = if (photo.isHalfWidth) 2f else 1f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minDurationMs = if (image == null) 0L else fullscreenScrollMinDurationMs(image.width * sourceStretchX, image.height.toFloat(),
            constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), speedLimitPercent)
        val progress = rememberFullscreenScrollProgress(image, isLoaded = image != null, isPaused, max(durationMs, minDurationMs), onDurationMs)
        if (image == null) return@BoxWithConstraints
        ComposableFullscreenScrollImage(image, photo.fullscreenScroll, progress.value, sourceStretchX, displaySqueezeX = 1f)
    }
}

/**
 * Scrolling progress (0..1) of the image [key], animated linearly over [durationMs] once [isLoaded].
 * Cancelling the animation (pause, new image) stops it where it is; resuming plays the remaining time.
 * [onDurationMs] is called with [durationMs] once [isLoaded], and again if [durationMs] changes.
 */
@Composable
private fun rememberFullscreenScrollProgress(key: Any?, isLoaded: Boolean, isPaused: Boolean, durationMs: Long,
                                             onDurationMs: (Long) -> Unit): Animatable<Float, AnimationVector1D> {
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key, isLoaded, durationMs) {
        if (isLoaded) onDurationMs(durationMs)
    }
    LaunchedEffect(key, isLoaded, isPaused, durationMs) {
        if (!isLoaded || isPaused) return@LaunchedEffect
        val remainingMs = ((1f - progress.value) * durationMs).roundToInt()
        progress.animateTo(1f, tween(remainingMs, easing = LinearEasing))
    }
    return progress
}

/**
 * One image scaled to fill (cover) its box, translated along its overflowing axis according to [progress] (0..1).
 * @param sourceStretchX : horizontal stretch giving the image its real aspect ratio
 * @param displaySqueezeX : horizontal squeeze applied by the display (0.5f on a half-width SBS display)
 */
@Composable
private fun ComposableFullscreenScrollImage(image: ImageBitmap, scroll: FullscreenScroll, progress: Float,
                                            sourceStretchX: Float, displaySqueezeX: Float) {
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        // computed in "real aspect" coordinates, i.e. as the viewer sees it once the display unsqueezes it
        val boxWidth = size.width / displaySqueezeX
        val boxHeight = size.height
        val imageWidth = image.width * sourceStretchX
        val imageHeight = image.height.toFloat()
        val scale = max(boxWidth / imageWidth, boxHeight / imageHeight)
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        // scrolling down/right: starts on the top/left edge (offset 0) and ends on the bottom/right edge
        val shift = if (scroll == FullscreenScroll.DOWN_RIGHT) progress else 1f - progress
        val offsetX = -(drawnWidth - boxWidth) * shift
        val offsetY = -(drawnHeight - boxHeight) * shift
        drawImage(
            image,
            dstOffset = IntOffset((offsetX * displaySqueezeX).roundToInt(), offsetY.roundToInt()),
            dstSize = IntSize((drawnWidth * displaySqueezeX).roundToInt(), drawnHeight.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
}
