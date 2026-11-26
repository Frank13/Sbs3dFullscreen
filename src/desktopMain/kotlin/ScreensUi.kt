import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.screens_built_in_display
import sbs3dViewer.resources.screens_current
import sbs3dViewer.resources.screens_mode
import sbs3dViewer.resources.screens_mode_duplicate
import sbs3dViewer.resources.screens_mode_duplicate_and_extend
import sbs3dViewer.resources.screens_mode_extend
import sbs3dViewer.resources.screens_mode_pc_screen_only
import sbs3dViewer.resources.screens_mode_second_screen_only
import sbs3dViewer.resources.screens_mode_single
import sbs3dViewer.resources.screens_new_screen_toast
import sbs3dViewer.resources.screens_primary
import sbs3dViewer.resources.screens_resolution
import sbs3dViewer.resources.screens_settings_label
import sbs3dViewer.resources.screens_settings_unavailable
import sbs3dViewer.resources.screens_unknown_display
import kotlin.time.Duration.Companion.milliseconds

/** [ConnectedScreen.displayName], or a localized fallback when the monitor reports no name at all. */
@Composable
fun screenLabel(screen: ConnectedScreen): String = screen.displayName
    ?: stringResource(if (screen.isBuiltIn) Res.string.screens_built_in_display else Res.string.screens_unknown_display)

/** "New screen connected: Samsung Odyssey G70NC (3840 × 2160)" - shared by the 2D and 3D toasts. */
@Composable
fun screenConnectedMessage(screen: ConnectedScreen): String =
    stringResource(Res.string.screens_new_screen_toast, screenLabel(screen), screen.widthPx, screen.heightPx)

/** Localized name of [mode], worded like the Windows Win+P "Project" menu. */
@Composable
fun screenModeLabel(mode: ScreenMode): String = stringResource(
    when (mode) {
        ScreenMode.PC_SCREEN_ONLY -> Res.string.screens_mode_pc_screen_only
        ScreenMode.SECOND_SCREEN_ONLY -> Res.string.screens_mode_second_screen_only
        ScreenMode.SINGLE -> Res.string.screens_mode_single
        ScreenMode.DUPLICATE -> Res.string.screens_mode_duplicate
        ScreenMode.EXTEND -> Res.string.screens_mode_extend
        ScreenMode.DUPLICATE_AND_EXTEND -> Res.string.screens_mode_duplicate_and_extend
    },
)

/**
 * The welcome settings dialog's "Screens" section: one row per connected monitor with its
 * resolution in physical pixels, under the current [ScreenMode], flagging the primary one and the one the app window is on.
 */
@Composable
fun ConnectedScreensSection(screens: List<ConnectedScreen>, mode: ScreenMode?, currentGdiDeviceName: String?) {
    Text(stringResource(Res.string.screens_settings_label))
    mode?.let {
        Text(stringResource(Res.string.screens_mode, screenModeLabel(it)), style = MaterialTheme.typography.bodySmall)
    }
    if (screens.isEmpty()) {
        Text(stringResource(Res.string.screens_settings_unavailable), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        screens.forEach { screen ->
            val isCurrent = screen.gdiDeviceName == currentGdiDeviceName
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(screenLabel(screen), fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                val tags = buildList {
                    add(stringResource(Res.string.screens_resolution, screen.widthPx, screen.heightPx))
                    if (screen.isPrimary) add(stringResource(Res.string.screens_primary))
                    if (isCurrent) add(stringResource(Res.string.screens_current))
                }
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 2D toast for the non-3D screens (Welcome, Gallery, playlist editor...), flashed at the bottom
 * of the window for 3 s each time [event] changes. The 3D screens use [ScreenConnectedStereoToast]
 * instead (see CLAUDE.md: no single-copy messages over SBS content).
 */
@Composable
fun ScreenConnectedToast(event: ScreenWatcher.NewScreenEvent?) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(event) {
        if (event == null) return@LaunchedEffect
        visible = true
        delay(3000.milliseconds)
        visible = false
    }
    Box(modifier = Modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = visible && event != null, enter = fadeIn(), exit = fadeOut()) {
            event?.let {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xE0323232))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(screenConnectedMessage(it.screen), color = Color.White)
                }
            }
        }
    }
}
