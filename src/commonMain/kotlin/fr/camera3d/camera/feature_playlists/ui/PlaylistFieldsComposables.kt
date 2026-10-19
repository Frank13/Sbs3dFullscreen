package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Portable versions of the playlist header-row composables previously defined inline in
// PlaylistFragment.kt (ComposableEditName, ComposableEditSubtitle,
// ComposableEditDefaultDuration, ComposablePlaylistMenu): same
// behavior, but every label/dialog-title/error string comes from the caller-supplied
// PlaylistScreenStrings instead of stringResource(R.string.x), so this file has no Android
// resource dependency. Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.EditParameterComposable
import fr.camera3d.camera.common.ui_components.PortableOkCancelDialog
import fr.camera3d.camera.common.ui_components.PortableParameterComposable
import fr.camera3d.camera.common.ui_components.snackbar
import fr.camera3d.camera.feature_playlists.domain.CreditsPlacement
import kotlinx.coroutines.CoroutineScope

/**
 * A row with the playlist name and edit icon; opens a dialog box and updates the view model.
 */
@Composable
fun ComposableEditName(
    name: String = "my playlist name",
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    onEditName: (String) -> Boolean,
    canRenamePlaylist: (String) -> Boolean = { true },
    strings: PlaylistScreenStrings,
) {
    EditParameterComposable(
        parameterValue = name,
        parameterName = strings.name,
        dialogTitle = strings.editPlaylistName,
        okLabel = strings.ok,
        saveLabel = strings.saveButton,
        cancelLabel = strings.cancel,
        onValidate = { newName ->
            if (newName != "" && !canRenamePlaylist(newName)) strings.playlistNameAlreadyExists else null
        },
        onNoChange = {
            snackbar(scope, snackbarHostState, strings.noModificationMade)
        },
        onDialogSave = { newName ->
            if (newName == "") {
                snackbar(scope, snackbarHostState, strings.nameCannotBeEmpty)
                true
            } else {
                try {
                    val done = onEditName(newName)
                    if (done) {
                        snackbar(scope, snackbarHostState, strings.playlistRenamed)
                    }
                    done
                } catch (e: Exception) {
                    if (e.message?.contains(" has no access to content") == true) {
                        snackbar(scope, snackbarHostState, strings.cannotSavePlaylist)
                    } else {
                        snackbar(scope, snackbarHostState, strings.errorWhileSavingPlaylist.format(e.message ?: ""))
                    }
                    true
                }
            }
        },
    )
}

/** Displays the playlist subtitle (shown under the title on the title slide) and enables editing it. */
@Composable
fun ComposableEditSubtitle(subtitle: String, onModifySubtitle: (String) -> Boolean, strings: PlaylistScreenStrings) {
    EditParameterComposable(
        parameterValue = subtitle,
        defaultValue = strings.pressHereToSetASubtitle,
        parameterName = strings.subtitle,
        dialogTitle = strings.editSubtitle,
        okLabel = strings.ok,
        saveLabel = strings.saveButton,
        cancelLabel = strings.cancel,
        onDialogSave = { newSubtitle -> onModifySubtitle(newSubtitle) },
    )
}

/**
 * Displays the playlist credits (first line only) and enables editing them as a multi-line text.
 */
@Composable
fun ComposableEditCredits(credits: String, onModifyCredits: (String) -> Boolean, strings: PlaylistScreenStrings) {
    var openDialog by remember { mutableStateOf(false) }
    var textFieldValue by remember { mutableStateOf(credits) }
    val lines = credits.lines()
    PortableParameterComposable(
        parameterName = strings.credits,
        parameterValue = if (credits.isBlank()) "" else lines.first() + if (lines.size > 1) " …" else "",
        defaultValue = strings.pressHereToSetCredits,
        onClick = {
            textFieldValue = credits
            openDialog = true
        },
    )
    if (openDialog) {
        PortableOkCancelDialog(
            dialogTitle = strings.editCredits,
            dialogOkLabel = strings.saveButton,
            dialogCancelLabel = strings.cancel,
            content = {
                TextField(
                    value = textFieldValue,
                    onValueChange = { textFieldValue = it },
                    minLines = 6,
                )
            },
            onDismissRequestCb = { openDialog = false },
            okCallback = {
                // only the trailing blank lines are removed: leading ones delay the credits on purpose
                val newCredits = textFieldValue.trimEnd()
                if (newCredits == credits || onModifyCredits(newCredits)) openDialog = false
            },
        )
    }
}

/** Choice between rolling the credits on the end slide or over the last image */
@Composable
fun ComposableEditCreditsPlacement(placement: CreditsPlacement, enabled: Boolean, onModifyPlacement: (CreditsPlacement) -> Boolean, strings: PlaylistScreenStrings) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f)) {
        Text(strings.creditsPlacement, style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CreditsPlacement.entries.forEach { p ->
                FilterChip(
                    selected = placement == p,
                    enabled = enabled,
                    onClick = { if (placement != p) onModifyPlacement(p) },
                    label = {
                        Text(when (p) {
                            CreditsPlacement.END_SLIDE -> strings.creditsPlacementEndSlide
                            CreditsPlacement.LAST_IMAGE -> strings.creditsPlacementLastImage
                        })
                    },
                )
            }
        }
    }
}

/**
 * Displays default duration and triggers onClick when tapped to request opening the edit dialog.
 * The dialog itself is [ComposableEditDefaultDurationDialog], kept separate (and its open/textfield
 * state hoisted by the caller) because on Android this row can be the last item before a LazyColumn's
 * dynamic content, which in landscape (short viewport) may not get composed at all - a dialog owned
 * locally would then be silently dismissed even though its "open" state remained true.
 */
@Composable
fun ComposableEditDefaultDuration(duration: Long, enabled: Boolean = true, strings: PlaylistScreenStrings, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PortableParameterComposable(
            parameterName = strings.defaultDuration,
            parameterValue = duration.toString() + "s",
            onClick = { if (enabled) onClick() },
        )
    }
}

@Composable
fun ComposableEditDefaultDurationDialog(
    currentDurationS: Long,
    durationTextFieldValue: String,
    onDurationTextFieldValueChange: (String) -> Unit,
    onDismissRequestCb: () -> Unit,
    onSave: (newDurationS: Long) -> Boolean,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    strings: PlaylistScreenStrings,
) {
    PortableOkCancelDialog(
        dialogTitle = strings.modifySlideshowDefaultDurationBetweenImages,
        dialogOkLabel = strings.ok,
        dialogCancelLabel = strings.cancel,
        content = {
            TextField(
                value = durationTextFieldValue,
                suffix = { Text("s") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                onValueChange = onDurationTextFieldValueChange,
            )
        },
        onDismissRequestCb = onDismissRequestCb,
        okCallback = {
            val newDuration = durationTextFieldValue.trim().toLongOrNull()
            if (newDuration != null && newDuration != currentDurationS) {
                try {
                    val done = onSave(newDuration)
                    if (!done) {
                        snackbar(scope, snackbarHostState, strings.defaultDurationShouldBe0And60000Ms)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains(" has no access to content") == true) {
                        snackbar(scope, snackbarHostState, strings.cannotSavePlaylist)
                    } else {
                        snackbar(scope, snackbarHostState, strings.errorWhileSavingPlaylist.format(e.message ?: ""))
                    }
                }
            }
            onDismissRequestCb()
        },
    )
}

/** Formats a scroll speed limit, e.g. 5f -> "5%", 2.5f -> "2.5%" */
fun formatScrollSpeedLimitPercent(percent: Float): String =
    (if (percent % 1f == 0f) percent.toInt().toString() else percent.toString()) + "%"

/**
 * Displays the scroll speed limit of "force fullscreen by scrolling" photos and triggers onClick when
 * tapped to request opening the edit dialog [ComposableEditScrollSpeedLimitDialog], see
 * [ComposableEditDefaultDuration] for why the dialog is separate.
 */
@Composable
fun ComposableEditScrollSpeedLimit(speedLimitPercent: Float, enabled: Boolean = true, strings: PlaylistScreenStrings, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        PortableParameterComposable(
            parameterName = strings.scrollSpeedLimit,
            parameterValue = formatScrollSpeedLimitPercent(speedLimitPercent),
            onClick = { if (enabled) onClick() },
        )
    }
}

@Composable
fun ComposableEditScrollSpeedLimitDialog(
    currentSpeedLimitPercent: Float,
    speedLimitTextFieldValue: String,
    onSpeedLimitTextFieldValueChange: (String) -> Unit,
    onDismissRequestCb: () -> Unit,
    onSave: (newSpeedLimitPercent: Float) -> Boolean,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
    strings: PlaylistScreenStrings,
) {
    PortableOkCancelDialog(
        dialogTitle = strings.modifyScrollSpeedLimit,
        dialogOkLabel = strings.ok,
        dialogCancelLabel = strings.cancel,
        content = {
            TextField(
                value = speedLimitTextFieldValue,
                suffix = { Text("%") },
                supportingText = { Text(strings.scrollSpeedLimitDocumentation) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                onValueChange = onSpeedLimitTextFieldValueChange,
            )
        },
        onDismissRequestCb = onDismissRequestCb,
        okCallback = {
            val newSpeedLimit = speedLimitTextFieldValue.trim().replace(',', '.').toFloatOrNull()
            if (newSpeedLimit == null) {
                snackbar(scope, snackbarHostState, strings.scrollSpeedLimitShouldBe0And100)
            } else if (newSpeedLimit != currentSpeedLimitPercent) {
                try {
                    val done = onSave(newSpeedLimit)
                    if (!done) {
                        snackbar(scope, snackbarHostState, strings.scrollSpeedLimitShouldBe0And100)
                    }
                } catch (e: Exception) {
                    if (e.message?.contains(" has no access to content") == true) {
                        snackbar(scope, snackbarHostState, strings.cannotSavePlaylist)
                    } else {
                        snackbar(scope, snackbarHostState, strings.errorWhileSavingPlaylist.format(e.message ?: ""))
                    }
                }
            }
            onDismissRequestCb()
        },
    )
}

/** The playlist screen's 3-dot menu: delete option + confirmation dialog. */
@Composable
fun ComposablePlaylistMenu(
    onDelete: () -> Boolean,
    onGoBack: () -> Boolean,
    playlistName: String = "",
    photoCount: Int = 0,
    strings: PlaylistScreenStrings,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    IconButton(
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary),
        onClick = { expanded = true },
    ) {
        Icon(Icons.Default.MoreVert, contentDescription = "More")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(strings.deletePlaylistOption) },
            onClick = {
                expanded = false
                showDeleteConfirmDialog = true
            },
        )
    }
    if (showDeleteConfirmDialog) {
        PortableOkCancelDialog(
            onDismissRequestCb = { showDeleteConfirmDialog = false },
            dialogTitle = strings.deletePlaylistConfirmTitle,
            dialogOkLabel = strings.deletePlaylistConfirmOk,
            dialogCancelLabel = strings.cancel,
            okCallback = {
                showDeleteConfirmDialog = false
                val isDeleted = onDelete()
                if (isDeleted) {
                    onGoBack()
                }
            },
        ) {
            Text(strings.deletePlaylistConfirmMessage.format(playlistName, photoCount))
        }
    }
}
