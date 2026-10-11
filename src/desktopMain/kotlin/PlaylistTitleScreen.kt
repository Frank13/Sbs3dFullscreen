
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.ui.ComposablePortableTitleSlide
import org.jetbrains.compose.resources.painterResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.icon

// Both the right and bottom padding, as a fraction of the screen HEIGHT (so they are equal).
private const val TitleIconPaddingFraction = 0.05f
private const val TitleIconHeightFraction = 0.1f

/**
 * The slideshow's title slide: the shared stereo visual (ComposablePortableTitleSlide, also used
 * by CameraSync3D's beamer secondary display and SIDE_BY_SIDE mode) plus the app icon in the
 * bottom-right corner of each eye-half (at screen depth, 0% shift) and a blinking keyboard hint,
 * since this app has no touch/Play button - Space/Right starts the slideshow (see Main.kt).
 * Under [shrinkControls] (AppViewModel.shrinkControls) the texts and icon are squeezed horizontally
 * by 2 around each eye-half's own center, like every other overlay (see ShrinkControls.kt).
 */
@Composable
fun PlaylistTitleScreen(playlist: Playlist, shrinkControls: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Stereo3DCursorHost(shrinkControls = shrinkControls) {
            SqueezedPerEyeHalf(active = shrinkControls) {
                ComposablePortableTitleSlide(
                    title = playlist.name,
                    subtitle = playlist.subtitle,
                    titleShiftPercent = playlist.titleZPercent / 100f,
                    titleStyle = playlist.titleStyle,
                    subtitleShiftPercent = playlist.subtitleZPercent / 100f,
                    subtitleStyle = playlist.subtitleStyle,
                )
            }
            TitleSlideIconOverlay(stereo = true, shrinkControls = shrinkControls)
        }
    }
}

/**
 * Squeezes [content] (a full-width stereo visual: left eye in the left half, right eye in the right
 * half) horizontally by 2 when [active], so that each eye still fills its WHOLE half of the screen:
 * [content] is laid out at twice the screen width (each eye as wide as the screen, so its percent-based
 * boxes/text wrap as on a full screen), then squeezed by 2 into its half. It is composed once per
 * half - squeezed toward that half's outer edge - and each copy is clipped to its own eye BEFORE the
 * squeeze (the clip sits inside the squeezed layer), since the other eye would otherwise land,
 * squeezed, partly inside this half and show up as a third copy. Needed because the shared title slide
 * (ComposablePortableTitleSlide) builds its own left/right Row internally, so there is no per-half
 * hook to apply [shrinkHorizontally] to. A no-op when inactive.
 */
@Composable
fun SqueezedPerEyeHalf(active: Boolean, content: @Composable () -> Unit) {
    if (!active) {
        content()
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val virtualWidth = maxWidth * 2
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f).clipToBounds()) {
                Box(
                    Modifier
                        .wrapContentWidth(Alignment.Start, unbounded = true)
                        .width(virtualWidth)
                        .fillMaxHeight()
                        .shrinkHorizontally(true, TransformOrigin(0f, 0.5f))
                        .drawWithContent { clipRect(right = size.width / 2) { this@drawWithContent.drawContent() } },
                ) { content() }
            }
            Box(Modifier.fillMaxSize().weight(1f).clipToBounds()) {
                Box(
                    Modifier
                        .wrapContentWidth(Alignment.End, unbounded = true)
                        .width(virtualWidth)
                        .fillMaxHeight()
                        .shrinkHorizontally(true, TransformOrigin(1f, 0.5f))
                        .drawWithContent { clipRect(left = size.width / 2) { this@drawWithContent.drawContent() } },
                ) { content() }
            }
        }
    }
}

/**
 * The app icon in the bottom-right corner of the title slide. [stereo] duplicates it once per eye-half
 * (at screen depth, 0% shift), as on the real slide; otherwise it is drawn once, as in the single-eye
 * style-editor preview. [shrinkControls] squeezes each stereo copy horizontally by 2 toward its half's right edge, so it stays in the corner.
 */
@Composable
fun TitleSlideIconOverlay(stereo: Boolean = false, shrinkControls: Boolean = false) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Under shrinkControls the squeeze toward the half's right edge (below) halves this padding on
        // purpose: the 3D monitor then doubles horizontal widths, so it ends up equal to the bottom one.
        val horizontalPadding = maxHeight * TitleIconPaddingFraction
        val verticalPadding = maxHeight * TitleIconPaddingFraction
        val iconSize = maxHeight * TitleIconHeightFraction
        val icon = @Composable {
            Image(
                painter = painterResource(Res.drawable.icon),
                contentDescription = null,
                modifier = Modifier
                    .padding(end = horizontalPadding, bottom = verticalPadding)
                    .size(iconSize),
            )
        }
        if (stereo) {
            Row(Modifier.fillMaxSize()) {
                repeat(2) { // left and right eye halves
                    Box(
                        Modifier.fillMaxSize().weight(1f).shrinkHorizontally(shrinkControls, TransformOrigin(1f, 0.5f)),
                        contentAlignment = Alignment.BottomEnd,
                    ) { icon() }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) { icon() }
        }
    }
}
