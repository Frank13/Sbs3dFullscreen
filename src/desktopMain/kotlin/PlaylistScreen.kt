import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import fr.camera3d.camera.common.ui_components.SwitchParameterComposable
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import fr.camera3d.camera.feature_playlists.ui.ComposableEditDefaultDuration
import fr.camera3d.camera.feature_playlists.ui.ComposableEditDefaultDurationDialog
import fr.camera3d.camera.feature_playlists.ui.ComposableEditName
import fr.camera3d.camera.feature_playlists.ui.ComposableEditSubtitle
import fr.camera3d.camera.feature_playlists.ui.ComposableEditTextStyleIcon
import fr.camera3d.camera.feature_playlists.ui.ComposablePlaylistItem
import fr.camera3d.camera.feature_playlists.ui.ComposablePlaylistMenu
import fr.camera3d.camera.feature_playlists.ui.PlaylistScreenStrings
import fr.camera3d.camera.feature_playlists.ui.TextStyleEditorStrings
import fr.camera3d.camera.feature_playlists.ui.TextStyleTarget
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.*
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

// Number of header items (name+style row/subtitle+style row/soundtrack/automated-switch/duration) placed
// before the photo items in the LazyColumn - kept in sync with PlaylistFragment.kt's Android
// counterpart so the drag-to-reorder index math (from/to - PLAYLIST_HEADER_ITEM_COUNT) matches.
private const val PLAYLIST_HEADER_ITEM_COUNT = 5

/**
 * Playlist editor screen: same fields/behavior as CameraSync3D's PlaylistFragment, built from the
 * same shared composables (ComposableEditName, ComposableEditSubtitle, ComposableEditTextStyleIcon,
 * ComposableEditDefaultDuration(Dialog), ComposablePlaylistItem, ComposablePlaylistMenu - synced
 * from Android via tools/sync-from-android.ps1) so the two apps can't silently drift apart on what
 * a "playlist" contains.
 */
@Composable
fun PlaylistScreen(
    playlist: Playlist,
    onAddPhotos: () -> Unit,
    onAddMusic: () -> Unit,
    onRemoveSoundtrackItem: (Int) -> Unit,
    onPlay: () -> Boolean,
    onBack: () -> Unit,
    onEditName: (String) -> Boolean,
    canRenamePlaylist: (String) -> Boolean,
    onModifyDefaultDuration: (Long) -> Boolean,
    onModifyIsAutomated: (Boolean) -> Boolean,
    onModifySubtitle: (String) -> Boolean,
    onModifyTitleZPercent: (Float) -> Boolean,
    onModifySubtitleZPercent: (Float) -> Boolean,
    onModifyTitleStyle: (TextStyleConfig) -> Boolean,
    onModifySubtitleStyle: (TextStyleConfig) -> Boolean,
    onReorderPhotos: (List<PlaylistItem>) -> Unit,
    onDelete: () -> Boolean,
    onOpenPlaylistItem: (Int) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val vlcMissingToast = stringResource(Res.string.vlc_missing_cannot_play_toast)

    val strings = PlaylistScreenStrings(
        ok = stringResource(Res.string.ok_button),
        saveButton = stringResource(Res.string.playlist_save_button),
        cancel = stringResource(Res.string.cancel_button),
        name = stringResource(Res.string.playlist_name_label),
        editPlaylistName = stringResource(Res.string.playlist_edit_name_dialog_title),
        playlistNameAlreadyExists = stringResource(Res.string.playlist_name_already_exists),
        noModificationMade = stringResource(Res.string.playlist_no_modification_made),
        nameCannotBeEmpty = stringResource(Res.string.playlist_name_cannot_be_empty),
        playlistRenamed = stringResource(Res.string.playlist_renamed),
        cannotSavePlaylist = stringResource(Res.string.playlist_cannot_save),
        errorWhileSavingPlaylist = stringResource(Res.string.playlist_error_while_saving),
        subtitle = stringResource(Res.string.playlist_subtitle_label),
        pressHereToSetASubtitle = stringResource(Res.string.playlist_subtitle_placeholder),
        editSubtitle = stringResource(Res.string.playlist_edit_subtitle_dialog_title),
        titleZ = stringResource(Res.string.playlist_title_z_documentation),
        subtitleZ = stringResource(Res.string.playlist_subtitle_z_documentation),
        automatedSlideshow = stringResource(Res.string.playlist_automated_slideshow),
        defaultDuration = stringResource(Res.string.playlist_default_duration_label),
        modifySlideshowDefaultDurationBetweenImages = stringResource(Res.string.playlist_modify_default_duration_dialog_title),
        defaultDurationShouldBe0And60000Ms = stringResource(Res.string.playlist_default_duration_range_error),
        deletePlaylistOption = stringResource(Res.string.playlist_delete_option),
        deletePlaylistConfirmTitle = stringResource(Res.string.playlist_delete_confirm_title),
        deletePlaylistConfirmOk = stringResource(Res.string.playlist_delete_confirm_ok),
        deletePlaylistConfirmMessage = stringResource(Res.string.playlist_delete_confirm_message),
    )

    val textStyleEditorStrings = TextStyleEditorStrings(
        save = stringResource(Res.string.playlist_save_button),
        cancel = stringResource(Res.string.cancel_button),
        preview = stringResource(Res.string.playlist_text_style_preview),
        fontOptionsSection = stringResource(Res.string.playlist_text_style_font_options_section),
        fontSize = stringResource(Res.string.playlist_text_style_font_size),
        fontFamily = stringResource(Res.string.playlist_text_style_font_family),
        bold = stringResource(Res.string.playlist_text_style_bold),
        italic = stringResource(Res.string.playlist_text_style_italic),
        underline = stringResource(Res.string.playlist_text_style_underline),
        colorOptionsSection = stringResource(Res.string.playlist_text_style_color_options_section),
        textColor = stringResource(Res.string.playlist_text_style_text_color),
        backgroundOpacity = stringResource(Res.string.playlist_text_style_background_opacity),
        backgroundColor = stringResource(Res.string.playlist_text_style_background_color),
        positionSection = stringResource(Res.string.playlist_text_style_position_section),
        topPercent = stringResource(Res.string.playlist_text_style_top_percent),
        leftPercent = stringResource(Res.string.playlist_text_style_left_percent),
        rightPercent = stringResource(Res.string.playlist_text_style_right_percent),
        fontFamilyDefault = stringResource(Res.string.playlist_text_style_font_family_default),
        fontFamilySerif = stringResource(Res.string.playlist_text_style_font_family_serif),
        fontFamilySansSerif = stringResource(Res.string.playlist_text_style_font_family_sans_serif),
        fontFamilyMonospace = stringResource(Res.string.playlist_text_style_font_family_monospace),
        fontFamilyCursive = stringResource(Res.string.playlist_text_style_font_family_cursive),
        depthSection = stringResource(Res.string.playlist_text_style_depth_section),
        depthPercent = stringResource(Res.string.playlist_text_style_depth),
    )

    // Hoisted like on Android: the duration row is the last header item before the photo list, so
    // a dialog owned by the row itself could be silently dismissed if the row isn't recomposed.
    var showDurationDialog by rememberSaveable { mutableStateOf(false) }
    var durationTextFieldValue by rememberSaveable { mutableStateOf("") }

    // Local working list for live drag visual feedback; kept in sync with playlist.photos when no
    // drag is in progress (same pattern as Android's ComposablePlaylistFragment).
    var workingPhotos by remember { mutableStateOf(playlist.photos) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(playlist.photos) {
        if (!isDragging) workingPhotos = playlist.photos
    }

    val reorderState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        onMove = { from, to ->
            val fromIndex = from.index - PLAYLIST_HEADER_ITEM_COUNT
            val toIndex = to.index - PLAYLIST_HEADER_ITEM_COUNT
            workingPhotos = workingPhotos.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        },
    )

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = playlist.name,
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
            val onDeleteTracked = {
                Analytics.logEvent("menu_item_click", mapOf("item" to "delete_playlist"))
                onDelete()
            }
            ComposablePlaylistMenu(onDeleteTracked, { onBack(); true }, playlist.name, playlist.photos.size, strings = strings)
        },
        bottomBar = {},
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = { if (!onPlay()) scope.launch { snackbarHostState.showSnackbar(vlcMissingToast) } },
                    shape = CircleShape,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(Res.string.playlist_play_button))
                }
                var showAddMenu by remember { mutableStateOf(false) }
                Box {
                    FloatingActionButton(onClick = { showAddMenu = true }, shape = CircleShape) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.playlist_add_photos_dialog_title))
                    }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.playlist_add_photos_menu_item)) },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
                            onClick = { showAddMenu = false; onAddPhotos() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.playlist_add_music_button)) },
                            leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                            onClick = { showAddMenu = false; onAddMusic() },
                        )
                    }
                }
            }
        },
        snackbarHostState = snackbarHostState,
        scrollable = false,
        screenContent = {
            BoxWithConstraints {
                val imageWidth = maxWidth / 2

                // Filenames are normally unique within a playlist (AppViewModel.
                // addPhotosToEditingPlaylist dedupes on import), but a playlist saved before that
                // fix existed could already have two items sharing a name - falling back to an
                // index-suffixed key only for those keeps the common case's key stable across
                // reorders/animations while still avoiding LazyColumn's "duplicate key" crash for
                // already-broken playlists.
                val duplicateFilenames = remember(workingPhotos) {
                    workingPhotos.groupingBy { it.filename }.eachCount().filterValues { it > 1 }.keys
                }
                fun keyFor(index: Int, photo: PlaylistItem) =
                    if (photo.filename in duplicateFilenames) "${photo.filename}#$index" else photo.filename

                LazyColumn(
                    state = lazyListState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ComposableEditName(playlist.name, snackbarHostState, scope, onEditName, canRenamePlaylist, strings = strings)
                            }
                            ComposableEditTextStyleIcon(
                                label = stringResource(Res.string.playlist_title_style_label),
                                titleText = playlist.name,
                                titleStyle = playlist.titleStyle,
                                subtitleText = playlist.subtitle,
                                subtitleStyle = playlist.subtitleStyle,
                                editingTarget = TextStyleTarget.TITLE,
                                zPercent = playlist.titleZPercent,
                                zDocumentation = strings.titleZ,
                                strings = textStyleEditorStrings,
                                onSave = { style ->
                                    Analytics.logEvent("text_style_saved", mapOf("target" to "title"))
                                    onModifyTitleStyle(style)
                                },
                                onSaveZPercent = onModifyTitleZPercent,
                                previewOverlay = { TitleSlideIconOverlay() },
                            )
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                ComposableEditSubtitle(playlist.subtitle, onModifySubtitle, strings = strings)
                            }
                            ComposableEditTextStyleIcon(
                                label = stringResource(Res.string.playlist_subtitle_style_label),
                                titleText = playlist.name,
                                titleStyle = playlist.titleStyle,
                                subtitleText = playlist.subtitle,
                                subtitleStyle = playlist.subtitleStyle,
                                editingTarget = TextStyleTarget.SUBTITLE,
                                zPercent = playlist.subtitleZPercent,
                                zDocumentation = strings.subtitleZ,
                                strings = textStyleEditorStrings,
                                onSave = { style ->
                                    Analytics.logEvent("text_style_saved", mapOf("target" to "subtitle"))
                                    onModifySubtitleStyle(style)
                                },
                                onSaveZPercent = onModifySubtitleZPercent,
                                previewOverlay = { TitleSlideIconOverlay() },
                            )
                        }
                    }
                    item {
                        ComposableSoundtrackEditor(playlist.soundtrack, onRemoveSoundtrackItem)
                    }
                    item {
                        SwitchParameterComposable(
                            parameterName = strings.automatedSlideshow,
                            checked = playlist.isAutomated,
                            documentation = "",
                            onCheckedChange = { onModifyIsAutomated(it) },
                        )
                    }
                    item {
                        Box(Modifier.padding(start = 16.dp)) {
                            ComposableEditDefaultDuration(
                                duration = playlist.defaultDurationS,
                                enabled = playlist.isAutomated,
                                strings = strings,
                                onClick = {
                                    durationTextFieldValue = playlist.defaultDurationS.toString()
                                    showDurationDialog = true
                                },
                            )
                        }
                    }
                    itemsIndexed(workingPhotos, key = ::keyFor) { index, photo ->
                        ReorderableItem(reorderState, key = keyFor(index, photo)) { dragging ->
                            val elevation = if (dragging) 8.dp else 4.dp
                            val reorderModifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { isDragging = true },
                                onDragStopped = {
                                    isDragging = false
                                    onReorderPhotos(workingPhotos)
                                },
                            )
                            // Coil has no video-frame decoder on desktop/JVM (unlike Android's
                            // coil3-video), so the shared ComposablePlaylistItem's
                            // SubcomposeAsyncImage would just show a broken-image icon for a
                            // video - ComposableVideoPlaylistItem swaps in a real libVLC-extracted
                            // frame instead, see VideoThumbnail.kt's doc comment.
                            if (photo.isVideo) {
                                ComposableVideoPlaylistItem(
                                    index = index,
                                    photo = photo,
                                    shadowElevation = elevation,
                                    onOpenPlaylistItem = onOpenPlaylistItem,
                                    imageWidth = imageWidth,
                                    imageHeight = 200.dp,
                                    reorderModifier = reorderModifier,
                                )
                            } else {
                                ComposablePlaylistItem(
                                    index = index,
                                    photo = photo,
                                    shadowElevation = elevation,
                                    onOpenPlaylistItem = onOpenPlaylistItem,
                                    imageWidth = imageWidth,
                                    imageHeight = 200.dp,
                                    reorderModifier = reorderModifier,
                                )
                            }
                        }
                    }
                }
                if (showDurationDialog) {
                    ComposableEditDefaultDurationDialog(
                        currentDurationS = playlist.defaultDurationS,
                        durationTextFieldValue = durationTextFieldValue,
                        onDurationTextFieldValueChange = { durationTextFieldValue = it },
                        onDismissRequestCb = { showDurationDialog = false },
                        onSave = onModifyDefaultDuration,
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        strings = strings,
                    )
                }
            }
        },
    )
}

/**
 * Lists the playlist soundtrack (audio files played in order during the slideshow) with a remove
 * button per track - desktop counterpart of PlaylistFragment's private ComposableSoundtrackEditor.
 */
@Composable
private fun ComposableSoundtrackEditor(soundtrack: List<String>, onRemove: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(stringResource(Res.string.soundtrack_title), style = MaterialTheme.typography.titleMedium)
        if (soundtrack.isEmpty()) {
            Text(stringResource(Res.string.soundtrack_empty), style = MaterialTheme.typography.bodySmall)
        }
        soundtrack.forEachIndexed { index, filename ->
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. $filename", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { onRemove(index) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.soundtrack_remove))
                }
            }
        }
    }
}
