import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.ui.ComposableItemComment
import fr.camera3d.camera.feature_playlists.ui.ComposableItemCommentZPercent
import fr.camera3d.camera.feature_playlists.ui.ComposableItemDuration
import fr.camera3d.camera.feature_playlists.ui.ComposableItemHalfWidth
import fr.camera3d.camera.feature_playlists.ui.ComposableItemMenu
import fr.camera3d.camera.feature_playlists.ui.ComposableItemMuted
import fr.camera3d.camera.feature_playlists.ui.ComposableItemPhoto
import fr.camera3d.camera.feature_playlists.ui.PlaylistItemScreenStrings
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.*

/**
 * Playlist item detail screen: same fields/behavior as CameraSync3D's PlaylistItemFragment, built
 * from the same shared composables (ComposableItemComment, ComposableItemCommentZPercent,
 * ComposableItemDuration, ComposableItemHalfWidth, ComposableItemMenu - synced from Android via
 * tools/sync-from-android.ps1). Unlike Android, the photo isn't clickable - there's no desktop
 * equivalent of Android's anaglyph editor.
 */
@Composable
fun PlaylistItemScreen(
    photo: PlaylistItem,
    isPlaylistManual: Boolean,
    onBack: () -> Unit,
    onModifyComment: (String) -> Boolean,
    onModifyCommentZPercent: (Float) -> Boolean,
    onModifyDuration: (Int) -> Boolean,
    onModifyHalfWidth: (Boolean) -> Unit,
    onModifyMuted: (Boolean) -> Unit,
    onDelete: () -> Boolean,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val strings = PlaylistItemScreenStrings(
        ok = stringResource(Res.string.ok_button),
        saveButton = stringResource(Res.string.playlist_save_button),
        cancel = stringResource(Res.string.cancel_button),
        comment = stringResource(Res.string.playlist_item_comment),
        editComment = stringResource(Res.string.playlist_item_edit_comment_dialog_title),
        pressHereToSetAComment = stringResource(Res.string.playlist_item_comment_placeholder),
        pressHereToSetAVideoComment = stringResource(Res.string.playlist_item_comment_placeholder_video),
        zAltitudePercent = stringResource(Res.string.playlist_item_z_label),
        commentZDocumentation = stringResource(Res.string.playlist_item_z_documentation),
        editZAltitudePercent = stringResource(Res.string.playlist_item_edit_z_dialog_title),
        errorZMustBeAFloat = stringResource(Res.string.playlist_item_error_z_must_be_float),
        duration = stringResource(Res.string.playlist_item_duration_label),
        editDuration = stringResource(Res.string.playlist_item_edit_duration_dialog_title),
        usePlaylistDefaultValue = stringResource(Res.string.playlist_item_use_playlist_default_value),
        durationMustBePositive = stringResource(Res.string.playlist_item_duration_must_be_positive),
        durationManualModeWarning = stringResource(Res.string.playlist_item_duration_manual_mode_warning),
        halfWidth = stringResource(Res.string.playlist_item_half_width_label),
        halfWidthDocumentation = stringResource(Res.string.playlist_item_half_width_documentation),
        halfWidthDocumentationVideo = stringResource(Res.string.playlist_item_half_width_documentation_video),
        muted = stringResource(Res.string.playlist_item_muted_label),
        mutedDocumentation = stringResource(Res.string.playlist_item_muted_documentation),
        deletePhotoOption = stringResource(
            if (photo.isVideo) Res.string.playlist_item_delete_option_video else Res.string.playlist_item_delete_option
        ),
    )

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(
            if (photo.isVideo) Res.string.playlist_item_edit_title_video else Res.string.playlist_item_edit_title
        ),
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
                Analytics.logEvent("menu_item_click", mapOf("item" to "delete_photo"))
                onDelete()
            }
            ComposableItemMenu(onDelete = onDeleteTracked, onGoBack = { onBack(); true }, strings = strings)
        },
        bottomBar = {},
        snackbarHostState = snackbarHostState,
        screenContent = {
            if (photo.isVideo) {
                ComposableVideoThumbnail(
                    file = playlistItemFile(photo.imageUriString),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                )
            } else {
                ComposableItemPhoto(photo.imageUriString)
            }
            ComposableItemComment(photo.comment, isVideo = photo.isVideo, onModifyComment = onModifyComment, strings = strings)
            if (!photo.isVideo) {
                ComposableItemCommentZPercent(photo.commentZPercent, snackbarHostState, scope, onModifyCommentZPercent, strings)
                ComposableItemDuration(photo.durationS, snackbarHostState, scope, onModifyDuration, strings, isManualMode = isPlaylistManual)
            }
            ComposableItemHalfWidth(photo.isHalfWidth, isVideo = photo.isVideo, onModifyHalfWidth = onModifyHalfWidth, strings = strings)
            if (photo.isVideo) {
                ComposableItemMuted(photo.isMuted, onModifyMuted = onModifyMuted, strings = strings)
            }
            Spacer(modifier = Modifier.height(24.dp))
        },
    )
}
