
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.ui.ComposablePortableEndSlide
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.icon
import sbs3dViewer.resources.playlist_end_label
import sbs3dViewer.resources.playlist_end_slide_hint

private const val EndIconPaddingFraction = 0.05f
private const val EndLabelHeightFraction = 0.2f

/**
 * The slideshow's end slide: the shared stereo visual (ComposablePortableEndSlide, also used by
 * CameraSync3D's beamer secondary display and SIDE_BY_SIDE mode) plus the app icon in each corner
 * of each half (like CameraSync3D's ComposableEndLogosHalf), since this app has no touch/tap-for-buttons -
 * Space/Right replays from the title slide, Escape exits (see Main.kt). Under [shrinkControls]
 * (AppViewModel.shrinkControls) the label and icons are squeezed horizontally by 2 per eye-half,
 * like the title slide (see [SqueezedPerEyeHalf]).
 * While [creditsPlaylist] is set (credits placed on the end slide, see AppViewModel.isCreditsRolling), its
 * credits roll on black first; the "the end" animation starts once [onCreditsEnded] cleared it.
 */
@Composable
fun PlaylistEndScreen(shrinkControls: Boolean = false, creditsPlaylist: Playlist? = null, onCreditsEnded: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Stereo3DCursorHost(shrinkControls = shrinkControls) {
            if (creditsPlaylist != null) {
                PlaylistCreditsRoll(creditsPlaylist, shrinkControls, onCreditsEnded)
                return@Stereo3DCursorHost
            }
            SqueezedPerEyeHalf(active = shrinkControls) {
                ComposablePortableEndSlide(
                    endLabel = stringResource(Res.string.playlist_end_label),
                    labelHeightFraction = EndLabelHeightFraction,
                )
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val halfWidth = maxWidth / 2
                    // 5% of the height on every side. Horizontally it is squeezed by 2 along with the icons
                    // under shrinkControls (SqueezedPerEyeHalf), so it still looks equal to the vertical one
                    // once the 3D monitor doubles widths again.
                    val iconPadding = maxHeight * EndIconPaddingFraction
                    // app icon in each corner of each half, each corner at a different depth shift
                    Row(Modifier.fillMaxSize()) {
                        EndIconsHalf(Modifier.fillMaxSize().weight(1f), halfWidth, iconPadding, shiftSign = -1f) // left
                        EndIconsHalf(Modifier.fillMaxSize().weight(1f), halfWidth, iconPadding, shiftSign = 1f) // right
                    }
                }
            }
        }
    }
}

/**
 * The app icon in each of the 4 corners of a stereo half, each corner at its own depth shift.
 * @param shiftSign -1f for the left half, 1f for the right half (mirrors the shift direction)
 */
@Composable
private fun EndIconsHalf(modifier: Modifier, halfWidth: Dp, iconPadding: Dp, shiftSign: Float) {
    val cornerShiftPercents = listOf(
        Alignment.TopStart to 0.015f,
        Alignment.TopEnd to 0.005f,
        Alignment.BottomStart to -0.005f,
        Alignment.BottomEnd to -0.015f,
    )
    // icons fade in one by one, starting with the farthest (largest depth shift) after 1s, then every 1s after
    val orderedByDistance = cornerShiftPercents.sortedByDescending { (_, shiftPercent) -> shiftPercent }
    Box(modifier) {
        orderedByDistance.forEachIndexed { index, (alignment, shiftPercent) ->
            val iconAlpha = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(1000L * (index + 1))
                iconAlpha.animateTo(1f, animationSpec = tween(durationMillis = 500))
            }
            Image(
                painter = painterResource(Res.drawable.icon),
                contentDescription = null,
                modifier = Modifier
                    .align(alignment)
                    .padding(iconPadding)
                    .size(100.dp)
                    .offset(x = halfWidth * shiftPercent * shiftSign / 2)
                    .alpha(iconAlpha.value),
            )
        }
    }
}
