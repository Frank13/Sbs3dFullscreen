import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.close
import sbs3dViewer.resources.screens_samsung_3d_not_alone_warning
import sbs3dViewer.resources.screens_built_in_display
import sbs3dViewer.resources.screens_current
import sbs3dViewer.resources.screens_list_title
import sbs3dViewer.resources.screens_mode
import sbs3dViewer.resources.screens_mode_duplicate
import sbs3dViewer.resources.screens_mode_duplicate_and_extend
import sbs3dViewer.resources.screens_mode_extend
import sbs3dViewer.resources.screens_mode_pc_screen_only
import sbs3dViewer.resources.screens_mode_second_screen_only
import sbs3dViewer.resources.screens_mode_single
import sbs3dViewer.resources.screens_native_resolution
import sbs3dViewer.resources.screens_new_screen_toast
import sbs3dViewer.resources.screens_primary
import sbs3dViewer.resources.screens_resolution
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
 * Body of the welcome settings dialog's "Screens" section (the heading is the caller's): one row per connected monitor with its
 * resolution in physical pixels, under the current [ScreenMode], flagging the primary one and the one the app window is on.
 */
@Composable
fun ConnectedScreensSection(screens: List<ConnectedScreen>, mode: ScreenMode?, currentGdiDeviceName: String?) {
    // The "Screens" heading itself (and the indentation) comes from the caller's SettingsSection card.
    Column {
        mode?.let {
            Text(stringResource(Res.string.screens_mode, screenModeLabel(it)), style = MaterialTheme.typography.bodySmall)
        }
        if (screens.isEmpty()) {
            Text(stringResource(Res.string.screens_settings_unavailable), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            stringResource(Res.string.screens_list_title),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
            screens.forEach { screen ->
                val isCurrent = screen.gdiDeviceName == currentGdiDeviceName
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•", modifier = Modifier.padding(end = 8.dp))
                    Column {
                        Text(screenLabel(screen), fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        val tags = buildList {
                            add(stringResource(Res.string.screens_resolution, screen.widthPx, screen.heightPx))
                            // Shown only when it differs, e.g. in "duplicate" mode where the shared
                            // desktop runs at the smaller screen's resolution.
                            val nativeW = screen.nativeWidthPx
                            val nativeH = screen.nativeHeightPx
                            if (nativeW != null && nativeH != null && (nativeW != screen.widthPx || nativeH != screen.heightPx)) {
                                add(stringResource(Res.string.screens_native_resolution, nativeW, nativeH))
                            }
                            if (screen.isPrimary) add(stringResource(Res.string.screens_primary))
                            if (isCurrent) add(stringResource(Res.string.screens_current))
                        }
                        Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Thick (~2 cm) red warning band pinned to the bottom of the 2D screens (Welcome, Gallery), shown
 * while the Samsung 3D monitor shares the desktop with other screens (see
 * [ScreenWatcher.showSamsung3DNotAloneWarning]); the round × in its top-right corner closes it.
 * The caller lays it out under the screen content (not over it), so nothing gets hidden behind it.
 * "Windows+P" is drawn as two key caps - the Windows logo key and P - with a plain "+" between
 * them, so nobody reads the + as a third key or has to know which key "Windows" is.
 */
@Composable
fun Samsung3DNotAloneWarningPanel(onClose: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 2 cm ≈ 76 dp (1 dp = 1/96 in at 100 % scaling).
                .heightIn(min = 76.dp)
                .background(WarningPanelRed),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp, end = 56.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningPanelRed, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(16.dp))
                // The string's %1$s/%2$s are the key-cap slots: fill them with private-use markers,
                // then swap each marker for an inline key-cap drawing.
                val raw = stringResource(Res.string.screens_samsung_3d_not_alone_warning, WindowsKeyMarker, PKeyMarker)
                val message = buildAnnotatedString {
                    raw.forEach { c ->
                        when (c) {
                            WindowsKeyMarker[0] -> appendInlineContent(WindowsKeyId, "Windows")
                            PKeyMarker[0] -> appendInlineContent(PKeyId, "P")
                            else -> append(c)
                        }
                    }
                }
                val keyCap = Placeholder(width = 1.7.em, height = 1.5.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
                Text(
                    message,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    inlineContent = mapOf(
                        WindowsKeyId to InlineTextContent(keyCap) { KeyCap { WindowsLogo(Modifier.fillMaxSize(0.55f)) } },
                        PKeyId to InlineTextContent(keyCap) {
                            // Line height trimmed to the glyph itself: the default one adds space
                            // above the letter (ascent/line gap), which made the P sit low on the cap.
                            val keyStyle = MaterialTheme.typography.titleMedium.let {
                                it.copy(
                                    lineHeight = it.fontSize,
                                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                                )
                            }
                            KeyCap { Text("P", color = KeyCapInk, style = keyStyle) }
                        },
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.close), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
}

private val WarningPanelRed = Color(0xFFC62828)
private val KeyCapInk = Color(0xFF202020)
private const val WindowsKeyMarker = ""
private const val PKeyMarker = ""
private const val WindowsKeyId = "windowsKey"
private const val PKeyId = "pKey"

/** A keyboard key: light rounded cap with a darker bottom edge, [content] centered on it. */
@Composable
private fun KeyCap(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color(0xFF9E9E9E))
            .padding(bottom = 3.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The Windows logo (four panes), as printed on the keyboard's Windows key. */
@Composable
private fun WindowsLogo(modifier: Modifier) {
    Canvas(modifier = modifier) {
        val gap = size.minDimension * 0.08f
        val pane = (size.minDimension - gap) / 2f
        val left = (size.width - size.minDimension) / 2f
        val top = (size.height - size.minDimension) / 2f
        for (row in 0..1) for (col in 0..1) {
            drawRect(
                color = Color(0xFF0078D4),
                topLeft = Offset(left + col * (pane + gap), top + row * (pane + gap)),
                size = Size(pane, pane),
            )
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
