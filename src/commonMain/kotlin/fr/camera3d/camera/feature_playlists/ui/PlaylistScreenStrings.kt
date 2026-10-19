package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Every string the shared playlist-editor composables (PlaylistFieldsComposables.kt) need,
// pre-resolved by the caller (stringResource(R.string.x) on Android, stringResource(Res.string.x)
// on desktop) so the shared composables never reference either platform's resource system.
// errorWhileSavingPlaylist/deletePlaylistConfirmMessage are format templates (String.format
// placeholders), applied by the composable that needs them. Sync via tools/sync-from-android.ps1.

data class PlaylistScreenStrings(
    val ok: String,
    val saveButton: String,
    val cancel: String,
    val name: String,
    val editPlaylistName: String,
    val playlistNameAlreadyExists: String,
    val noModificationMade: String,
    val nameCannotBeEmpty: String,
    val playlistRenamed: String,
    val cannotSavePlaylist: String,
    val errorWhileSavingPlaylist: String,
    val subtitle: String,
    val pressHereToSetASubtitle: String,
    val editSubtitle: String,
    val titleZ: String,
    val subtitleZ: String,
    val credits: String,
    val pressHereToSetCredits: String,
    val editCredits: String,
    val creditsZ: String,
    val creditsPlacement: String,
    val creditsPlacementEndSlide: String,
    val creditsPlacementLastImage: String,
    val automatedSlideshow: String,
    val defaultDuration: String,
    val modifySlideshowDefaultDurationBetweenImages: String,
    val defaultDurationShouldBe0And60000Ms: String,
    val scrollSpeedLimit: String,
    val modifyScrollSpeedLimit: String,
    val scrollSpeedLimitDocumentation: String,
    val scrollSpeedLimitShouldBe0And100: String,
    val deletePlaylistOption: String,
    val deletePlaylistConfirmTitle: String,
    val deletePlaylistConfirmOk: String,
    val deletePlaylistConfirmMessage: String,
)
