import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.clip
import sbs3dViewer.resources.image_settings_audio_output_label
import sbs3dViewer.resources.image_settings_halve_left_right_toggle_label
import sbs3dViewer.resources.image_settings_settings_label
import sbs3dViewer.resources.image_settings_shrink_controls_toggle_label
import sbs3dViewer.resources.welcome_settings_audio_unavailable
import sbs3dViewer.resources.welcome_settings_language_label
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.about_link_label
import sbs3dViewer.resources.cancel_button
import sbs3dViewer.resources.choose_jpeg_button
import sbs3dViewer.resources.choose_playlist_button
import sbs3dViewer.resources.file_dialog_title
import sbs3dViewer.resources.import_playlist_already_exists
import sbs3dViewer.resources.gallery_dialog_title
import sbs3dViewer.resources.ok_button
import sbs3dViewer.resources.opencv5_toggle_label
import sbs3dViewer.resources.open_gallery_button
import sbs3dViewer.resources.playlist_dialog_title
import sbs3dViewer.resources.playlist_list_button
import sbs3dViewer.resources.welcome
import sbs3dViewer.resources.welcome_desktop_shortcut_button
import sbs3dViewer.resources.welcome_desktop_shortcut_failed
import java.io.File
import javax.swing.JFileChooser

@Composable
fun WelcomeScreen(
    window: java.awt.Window,
    language: String?,
    onLanguageChosen: (String?) -> Unit,
    useNewOpenCv5: Boolean,
    onUseNewOpenCv5Chosen: (Boolean) -> Unit,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
    halveLeftRightImages: Boolean,
    onHalveLeftRightImagesChosen: (Boolean) -> Unit,
    shrinkControls: Boolean,
    onShrinkControlsChosen: (Boolean) -> Unit,
    onFilesChosen: (List<File>) -> Unit,
    onImportPlaylist: (File) -> Boolean,
    onOpenPlaylistList: () -> Unit,
    onOpenGallery: (File) -> Unit,
    onOpenAbout: () -> Unit,
) {
    val dialogTitle = stringResource(Res.string.file_dialog_title)
    val playlistDialogTitle = stringResource(Res.string.playlist_dialog_title)
    val galleryDialogTitle = stringResource(Res.string.gallery_dialog_title)
    var importErrorFolderName by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Re-checked each time the welcome screen is shown, so deleting the shortcut brings the button back.
    var showDesktopShortcutButton by remember { mutableStateOf(DesktopShortcut.isAvailable && !DesktopShortcut.exists()) }
    var showShortcutFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { showSettings = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(Res.string.image_settings_settings_label),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.welcome))
            Spacer(Modifier.height(16.dp))
            Button(onClick = {
                val files = chooseFiles(
                    window = window,
                    title = dialogTitle,
                    extensions = arrayOf("jpg", "jpeg", "mpo", "mp4", "mov", "mkv", "avi"),
                )
                if (files.isNotEmpty()) {
                    onFilesChosen(files)
                }
            }) {
                Text(stringResource(Res.string.choose_jpeg_button))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                val chooser = JFileChooser()
                chooser.dialogTitle = playlistDialogTitle
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                    chooser.selectedFile?.let { folder ->
                        if (!onImportPlaylist(folder)) {
                            importErrorFolderName = folder.name
                        }
                    }
                }
            }) {
                Text(stringResource(Res.string.choose_playlist_button))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenPlaylistList) {
                Text(stringResource(Res.string.playlist_list_button))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                chooseDirectory(window = window, title = galleryDialogTitle)?.let { folder -> onOpenGallery(folder) }
            }) {
                Text(stringResource(Res.string.open_gallery_button))
            }
            if (showDesktopShortcutButton) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { DesktopShortcut.create() }
                        Analytics.logEvent("create_desktop_shortcut", mapOf("success" to created))
                        if (created) showDesktopShortcutButton = false else showShortcutFailed = true
                    }
                }) {
                    Text(stringResource(Res.string.welcome_desktop_shortcut_button))
                }
            }
        }

        if (showSettings) {
            WelcomeSettingsDialog(
                language = language,
                onLanguageChosen = onLanguageChosen,
                useNewOpenCv5 = useNewOpenCv5,
                onUseNewOpenCv5Chosen = onUseNewOpenCv5Chosen,
                audioOutputDeviceId = audioOutputDeviceId,
                onAudioOutputDeviceChosen = onAudioOutputDeviceChosen,
                halveLeftRightImages = halveLeftRightImages,
                onHalveLeftRightImagesChosen = onHalveLeftRightImagesChosen,
                shrinkControls = shrinkControls,
                onShrinkControlsChosen = onShrinkControlsChosen,
                onOpenAbout = {
                    showSettings = false
                    onOpenAbout()
                },
                onDismiss = { showSettings = false },
            )
        }

        if (showShortcutFailed) {
            AlertDialog(
                onDismissRequest = { showShortcutFailed = false },
                title = { Text(stringResource(Res.string.welcome_desktop_shortcut_failed)) },
                text = {},
                confirmButton = {
                    TextButton(onClick = { showShortcutFailed = false }) {
                        Text(stringResource(Res.string.ok_button))
                    }
                },
            )
        }

        importErrorFolderName?.let { folderName ->
            AlertDialog(
                onDismissRequest = { importErrorFolderName = null },
                title = { Text(stringResource(Res.string.import_playlist_already_exists, folderName)) },
                text = {},
                confirmButton = {
                    TextButton(onClick = { importErrorFolderName = null }) {
                        Text(stringResource(Res.string.ok_button))
                    }
                },
            )
        }
    }
}

/**
 * Opened by the welcome screen's gear: app-wide language, OpenCV, display (halve left & right
 * images, shrink controls - same persisted AppViewModel values as ImageScreen's settings menu
 * toggles) and audio output settings.
 */
@Composable
private fun WelcomeSettingsDialog(
    language: String?,
    onLanguageChosen: (String?) -> Unit,
    useNewOpenCv5: Boolean,
    onUseNewOpenCv5Chosen: (Boolean) -> Unit,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
    halveLeftRightImages: Boolean,
    onHalveLeftRightImagesChosen: (Boolean) -> Unit,
    shrinkControls: Boolean,
    onShrinkControlsChosen: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Enumerated once per dialog opening: spinning up a libVLC factory is too heavy to do on
    // every recomposition of the welcome screen.
    val audioDevices = remember { listAudioOutputDevices() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.image_settings_settings_label)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(Res.string.welcome_settings_language_label))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val currentLanguage = language ?: LocalAppLocale.current.substring(0, 2)
                    LanguageButton(label = "EN", selected = currentLanguage == "en", onClick = { onLanguageChosen("en") })
                    LanguageButton(label = "FR", selected = currentLanguage == "fr", onClick = { onLanguageChosen("fr") })
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.opencv5_toggle_label), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = useNewOpenCv5, onCheckedChange = onUseNewOpenCv5Chosen)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.image_settings_halve_left_right_toggle_label), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = halveLeftRightImages, onCheckedChange = { trackMenuItem("welcome_halve_left_right"); onHalveLeftRightImagesChosen(it) })
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.image_settings_shrink_controls_toggle_label), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = shrinkControls, onCheckedChange = { trackMenuItem("welcome_shrink_controls"); onShrinkControlsChosen(it) })
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.image_settings_audio_output_label))
                if (audioDevices.isEmpty()) {
                    Text(
                        stringResource(Res.string.welcome_settings_audio_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                audioDevices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAudioOutputDeviceChosen(device.deviceId) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = audioOutputDeviceId == device.deviceId,
                            onClick = { onAudioOutputDeviceChosen(device.deviceId) },
                        )
                        Text(device.longName)
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onOpenAbout) {
                    Text(stringResource(Res.string.about_link_label))
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(Res.string.ok_button)) }
        },
    )
}

@Composable
private fun LanguageButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonBorder(enabled = true)
        },
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label)
    }
}
