import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.exif_update_failed
import sbs3dViewer.resources.exif_updated
import sbs3dViewer.resources.image_settings_exit_fullscreen_label
import sbs3dViewer.resources.image_settings_next_label
import sbs3dViewer.resources.image_settings_previous_label
import sbs3dViewer.resources.remote_control_position
import kotlin.time.Duration.Companion.milliseconds

/**
 * Content of the 2D "remote control" window Main.kt opens on the original display while the
 * fullscreen SBS viewer runs on another one (see Main.kt's remoteControlBounds): round
 * Previous/Next/Exit buttons, and - for a photo - the info panel, always shown and in plain 2D
 * ([InfoPanel] with `stereo = false`), driving the same [photoToolActions] as the viewer. A plain
 * 2D window, so its messages are ordinary 2D toasts (the "3D toasts only" rule is for the viewer).
 */
@Composable
fun RemoteControlContent(
    viewModel: AppViewModel,
    photoToolActions: PhotoToolActions,
    photoUnreadable: Boolean,
    onExitFullscreen: () -> Unit,
    onDialogOpenChanged: (Boolean) -> Unit,
) {
    var exifUpdatedToken by remember { mutableStateOf(0) }
    var exifFailedToken by remember { mutableStateOf(0) }
    Box(modifier = Modifier.fillMaxSize().background(RemoteBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RemoteRoundButton(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(Res.string.image_settings_previous_label)) {
                    Analytics.logEvent("remote_control_button", mapOf("button" to "previous"))
                    viewModel.showPreviousImage()
                }
                RemoteRoundButton(Icons.AutoMirrored.Filled.NavigateNext, stringResource(Res.string.image_settings_next_label)) {
                    Analytics.logEvent("remote_control_button", mapOf("button" to "next"))
                    viewModel.showNextImage()
                }
                RemoteRoundButton(Icons.Filled.FullscreenExit, stringResource(Res.string.image_settings_exit_fullscreen_label)) {
                    Analytics.logEvent("remote_control_button", mapOf("button" to "exit"))
                    onExitFullscreen()
                }
                Spacer(Modifier.width(8.dp))
                // Only for a plain photo/video selection: a playlist's index also counts its
                // title/end slides, so "n / total" would read wrong there.
                if (viewModel.playingPlaylist == null && viewModel.imageFiles.size > 1) {
                    Text(
                        stringResource(Res.string.remote_control_position, viewModel.currentImageIndex + 1, viewModel.imageFiles.size),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val file = viewModel.currentImage
                val isPhotoSlide = viewModel.screen == Screen.ImageView &&
                    (viewModel.playlistSlideKind == null || viewModel.playlistSlideKind == PlaylistSlideKind.PHOTO) &&
                    viewModel.currentPlaylistItem?.isVideo != true
                if (file != null && isPhotoSlide) {
                    val tools = viewModel.photoTools
                    InfoPanel(
                        file,
                        shrinkControls = false,
                        onDialogOpenChanged = onDialogOpenChanged,
                        onExifUpdated = { exifUpdatedToken++ },
                        onExifUpdateFailed = { exifFailedToken++ },
                        tools = PhotoToolsUiState(
                            hasAlignedPreview = tools.alignedPreview != null,
                            isAligning = viewModel.isAligning,
                            manualAlignMode = tools.manualAlignMode,
                            hasManualOffset = tools.manualAlignOffsetX != 0f || tools.manualAlignOffsetY != 0f,
                            cropMode = tools.cropMode,
                            hasCropRect = tools.cropRect != null,
                            spotIssuesMode = tools.spotIssuesMode,
                            hasSpotIssueRects = tools.spotIssueRects.isNotEmpty(),
                            clickAlignMode = tools.clickAlignMode,
                            hasClickAlignPoints = tools.clickAlignLeftPoint != null && tools.clickAlignRightPoint != null,
                            copyFromImageMode = tools.copyDirection != null,
                            hasCopyPainting = tools.copyMask?.isEmpty == false,
                            photoUnreadable = photoUnreadable,
                        ),
                        actions = photoToolActions,
                        showEditTools = viewModel.playingPlaylist == null,
                        stereo = false,
                    )
                } else if (file != null) {
                    Text(file.name, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(24.dp))
                }
            }
        }
        RemoteToast(exifUpdatedToken, stringResource(Res.string.exif_updated))
        RemoteToast(exifFailedToken, stringResource(Res.string.exif_update_failed))
    }
}

/** Round icon button on a filled circular background. */
@Composable
private fun RemoteRoundButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp))
    }
}

/** Flashes [message] at the bottom for 3 s each time [token] is bumped past 0 - same look as ScreenConnectedToast. */
@Composable
private fun RemoteToast(token: Int, message: String) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        visible = true
        delay(3000.milliseconds)
        visible = false
    }
    Box(modifier = Modifier.fillMaxSize().padding(bottom = 32.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE0323232))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(message, color = Color.White)
            }
        }
    }
}

private val RemoteBackground = Color(0xFF1E1E1E)
