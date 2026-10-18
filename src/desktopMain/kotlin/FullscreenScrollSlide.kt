import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.ui.ComposableFullscreenScrollStereoImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop part of a "force fullscreen by scrolling" playlist photo (PlaylistItem.fullscreenScroll) -
 * counterpart of Android's FullscreenScrollSlide.kt: splits the already decoded full-width SBS
 * [bitmap] into its left/right eye images (off the UI thread, these are big JPEGs) and hands them to
 * the shared ComposableFullscreenScrollStereoImages (PortableFullscreenScroll.kt, synced from
 * Android), which fills each half screen with its eye image and scrolls it over [durationMs].
 * [halveLeftRightImages] is the half-width SBS display mode (each half squeezed 2x, unsqueezed by the
 * 3D monitor), same meaning as in ImageScreen's StereoImage.
 */
@Composable
fun FullscreenScrollStereoImage(
    bitmap: ImageBitmap,
    photo: PlaylistItem,
    durationMs: Long,
    halveLeftRightImages: Boolean,
    speedLimitPercent: Float,
    onDurationMs: (Long) -> Unit,
) {
    var halves by remember(bitmap) { mutableStateOf<Pair<ImageBitmap, ImageBitmap>?>(null) }
    LaunchedEffect(bitmap) {
        halves = withContext(Dispatchers.Default) { bitmap.half(isLeft = true) to bitmap.half(isLeft = false) }
    }
    ComposableFullscreenScrollStereoImages(
        left = halves?.first,
        right = halves?.second,
        photo = photo,
        durationMs = durationMs,
        isPaused = false,
        displaySqueezeX = if (halveLeftRightImages) 0.5f else 1f,
        speedLimitPercent = speedLimitPercent,
        onDurationMs = onDurationMs,
    )
}

/** Copies the left or right half of a side-by-side bitmap into its own bitmap. */
private fun ImageBitmap.half(isLeft: Boolean): ImageBitmap {
    val halfWidth = (width / 2).coerceAtLeast(1)
    val result = ImageBitmap(halfWidth, height)
    Canvas(result).drawImageRect(
        image = this,
        srcOffset = IntOffset(if (isLeft) 0 else width - halfWidth, 0),
        srcSize = IntSize(halfWidth, height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(halfWidth, height),
        paint = Paint(),
    )
    return result
}
