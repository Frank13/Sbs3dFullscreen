import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import fr.camera3d.camera.feature_playlists.domain.Playlist
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.cancel_button
import sbs3dViewer.resources.create_playlist_button
import sbs3dViewer.resources.create_playlist_confirm
import sbs3dViewer.resources.create_playlist_dialog_title
import sbs3dViewer.resources.playlist_back_button
import sbs3dViewer.resources.playlist_list_empty
import sbs3dViewer.resources.playlist_list_refresh
import sbs3dViewer.resources.playlist_list_title
import sbs3dViewer.resources.playlist_name_already_exists
import sbs3dViewer.resources.playlist_name_label
import sbs3dViewer.resources.playlist_play_button
import sbs3dViewer.resources.vlc_missing_cannot_play_toast
import java.io.File

/**
 * Lists the playlist folders found under playlistsRoot: tapping a row's name opens it in the
 * editor, tapping the play icon starts its slideshow directly. Ported from CameraSync3D's
 * PlaylistsFragment.kt (ComposablePlaylistsFragment/ComposablePlaylist), minus the
 * Android-only SAF permission dance and the beamer/anaglyph launch modes this desktop app
 * doesn't have - it only ever plays side-by-side fullscreen.
 */
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Boolean,
    onCreatePlaylist: (String) -> Unit,
    canCreatePlaylist: (String) -> Boolean,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val vlcMissingToast = stringResource(Res.string.vlc_missing_cannot_play_toast)
    val playWithToast: (Playlist) -> Unit = { playlist ->
        if (!onPlayPlaylist(playlist)) scope.launch { snackbarHostState.showSnackbar(vlcMissingToast) }
    }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(Res.string.playlist_list_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.playlist_back_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        actionsContent = {
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(Res.string.playlist_list_refresh),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        bottomBar = {},
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    newPlaylistName = ""
                    showCreatePlaylistDialog = true
                },
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.create_playlist_button))
            }
        },
        snackbarHostState = snackbarHostState,
        scrollable = false,
        screenContent = {
            if (playlists.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.playlist_list_empty))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                    // Keyed by the real on-disk folder name (guaranteed unique within playlistsRoot),
                    // not Playlist.getDirName() - that strips spaces for index-file lookup purposes
                    // and can collapse two distinct folders (e.g. "essai 2" and "essai2") to the same key.
                    items(playlists, key = { File(it.absolutePath).name }) { playlist ->
                        ComposablePlaylistRow(playlist, onOpenPlaylist, playWithToast)
                    }
                }
            }
        },
    )

    if (showCreatePlaylistDialog) {
        val nameAlreadyExists = newPlaylistName.isNotBlank() && !canCreatePlaylist(newPlaylistName)
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text(stringResource(Res.string.create_playlist_dialog_title)) },
            text = {
                TextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.playlist_name_label)) },
                    isError = nameAlreadyExists,
                    supportingText = {
                        if (nameAlreadyExists) {
                            Text(stringResource(Res.string.playlist_name_already_exists))
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newPlaylistName.isNotBlank() && !nameAlreadyExists,
                    onClick = {
                        showCreatePlaylistDialog = false
                        onCreatePlaylist(newPlaylistName)
                    },
                ) {
                    Text(stringResource(Res.string.create_playlist_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }
}

@Composable
private fun ComposablePlaylistRow(
    playlist: Playlist,
    onOpenPlaylist: (Playlist) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
) {
    val rowInteractionSource = remember { MutableInteractionSource() }
    val isRowHovered by rowInteractionSource.collectIsHoveredAsState()
    val rowColor by animateColorAsState(
        if (isRowHovered) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
    )
    Surface(
        modifier = Modifier.padding(bottom = 8.dp).hoverable(rowInteractionSource),
        color = rowColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable { onOpenPlaylist(playlist) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(playlist.name)
            }
            Text(playlist.photos.size.toString(), modifier = Modifier.padding(end = 16.dp))
            val playButtonInteractionSource = remember { MutableInteractionSource() }
            val isPlayButtonHovered by playButtonInteractionSource.collectIsHoveredAsState()
            FilledIconButton(
                onClick = { onPlayPlaylist(playlist) },
                interactionSource = playButtonInteractionSource,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isPlayButtonHovered) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (isPlayButtonHovered) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.playlist_play_button))
            }
        }
    }
}
