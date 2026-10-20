import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.camera3d.camera.feature_playlists.domain.CreditsPlacement
import fr.camera3d.camera.feature_playlists.domain.FullscreenScroll
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import fr.camera3d.camera.feature_playlists.domain.isVideoFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { Welcome, About, Gallery, PlaylistList, PlaylistEdit, PlaylistItem, ImageView, VideoView }

/**
 * Mirrors CameraSync3D's SlideshowViewModel.UiType: a playlist slideshow is a title slide,
 * then its photos, then an end slide - not just a bare loop over image files.
 */
enum class PlaylistSlideKind { TITLE, PHOTO, END }

private val videoExtensions = setOf("mp4", "mov", "mkv", "avi")

/**
 * One auto-align/correct-zoom attempt's outcome - see AppViewModel.alignToast. [zoomScale]/
 * [rotationDegrees] are only populated for a successful [AlignKind.AFFINE] ("Correct Zoom") run -
 * see AutoAlign.AutoAlignResult - so AlignResultToast can report what was actually detected.
 */
data class AlignToast(val success: Boolean, val token: Int, val zoomScale: Float? = null, val rotationDegrees: Float? = null)

/**
 * One "Save" button attempt's outcome - see AppViewModel.saveToast. [exifError] is only
 * meaningful when [success] is true: an align/crop/spot-issues/keep-half save can write valid
 * pixels while still failing to carry the original's EXIF (in particular the Exif3d "3D
 * characteristics") across - see SavedFile/Exif.copyExif, whose failure message this carries
 * verbatim for display. Defaults to null so callers that save without going through that
 * pipeline (e.g. nothing currently) don't need to pass it.
 */
data class SaveToast(val success: Boolean, val token: Int, val exifError: String? = null)

/**
 * A Next/Previous navigation blocked by an unsaved auto-align/correct-zoom preview (see
 * AppViewModel.pendingNavigation) - ImageScreen shows a Save/Discard/Cancel dialog for it instead
 * of navigating straight away, so the preview (visible only in memory - see
 * PhotoToolsState.alignedPreview) isn't silently lost.
 */
enum class PendingNavigationDirection { NEXT, PREVIOUS }

// Every persisted setting AppViewModel owns directly (Gallery/Share/PlaylistEditor each keep their
// own, in their own files) - see Preferences.kt's BooleanPref/FloatPref/StringPref.
private val halveLeftRightImagesPref = BooleanPref("halveLeftRightImages", true)
private val keepBestOfEachOnlyPref = BooleanPref("keepBestOfEachOnly", false)
private val favoritesOnlyPref = BooleanPref("favoritesOnly", false)
private val excludeStereoIssuesPref = BooleanPref("excludeStereoIssues", false)
private val shrinkControlsPref = BooleanPref("shrinkControls", false)
private val useNewOpenCv5Pref = BooleanPref("useNewOpenCv5", false)
private val audioOutputDeviceIdPref = StringPref("audioOutputDeviceId", "")

// Range for AppViewModel.manualAlignStepPercent/onManualAlignStepPercentChosen - the settings
// dialog's stepper row only offers ManualAlignStepIncrement-sized steps within this range.
// Defaults to the minimum (0.5%), fine enough to still allow precise nudges.
private const val MinManualAlignStepPercent = 0.5f
private const val MaxManualAlignStepPercent = 10f
private val manualAlignStepPercentPref = FloatPref("manualAlignStepPercent", MinManualAlignStepPercent)

/**
 * Holds the app's screen/navigation state and the logic to mutate it, decoupled from the
 * `Window`/`WindowState` concerns (undecorated, placement) that stay in Main.kt since those
 * are tied directly to the Window composable's lifecycle.
 *
 * Gallery/Share/PlaylistEditor-specific state lives in their own sibling holders ([gallery]/
 * [share]/[playlistEditor], mirroring [photoTools]) rather than as more mutableStateOf properties
 * here - each is a large, self-contained cluster of fields/methods used from exactly one screen,
 * so grouping them keeps this class down to the state genuinely shared across screens (current
 * screen/file selection, filters, per-photo edit tools, pending navigation).
 */
class AppViewModel(initialFile: File?) {
    var screen by mutableStateOf(
        when {
            initialFile == null -> Screen.Welcome
            // Without VLC a launched video can't play: land on Welcome, where the missing-VLC sheet is raised (see init)
            initialFile.extension.lowercase() in videoExtensions -> if (Vlc.isInstalled()) Screen.VideoView else Screen.Welcome
            else -> Screen.ImageView
        }
    )
        private set
    var imageFiles by mutableStateOf(initialFile?.let { listOf(it) } ?: emptyList())
        private set
    var currentImageIndex by mutableStateOf(0)
        private set
    var language by mutableStateOf<String?>(null)
        private set
    // Mirrors CameraSync3D's useNewOpenCv5 toggle: SIFT+USAC_MAGSAC instead of ORB+RANSAC for
    // auto-align. Persisted (useNewOpenCv5Pref above) so the choice survives app restarts.
    var useNewOpenCv5 by mutableStateOf(useNewOpenCv5Pref.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, showNextImage/showPreviousImage/
    // advanceSlideshow skip over any photo that isn't the highest raw/edited version in its group
    // (see GalleryScreen.kt's bestVersionsOnly). Persisted (keepBestOfEachOnlyPref below)
    // since it's a durable viewing preference, not tied to the current session.
    var keepBestOfEachOnly by mutableStateOf(keepBestOfEachOnlyPref.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, showNextImage/showPreviousImage/
    // advanceSlideshow skip over any photo whose EXIF3D "favorite" flag isn't set (see
    // Exif3d.getFavoriteFromExif). Combines with keepBestOfEachOnly/excludeStereoIssues (see
    // visiblePhotos). Persisted (favoritesOnlyPref above) for the same reason as
    // keepBestOfEachOnly: it's a durable viewing preference, not tied to the current session.
    var favoritesOnly by mutableStateOf(favoritesOnlyPref.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, showNextImage/showPreviousImage/
    // advanceSlideshow skip over any photo whose EXIF3D "warning" (stereo issue) flag is set (see
    // Exif3d.getWarningFromExif). Combines with keepBestOfEachOnly/favoritesOnly (see
    // visiblePhotos). Persisted (excludeStereoIssuesPref above) for the same reason as
    // keepBestOfEachOnly: it's a durable viewing preference, not tied to the current session.
    var excludeStereoIssues by mutableStateOf(excludeStereoIssuesPref.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, the combined L+R photo is squeezed
    // horizontally by 2 before display, matching the input a Half-SBS 3D monitor expects (each eye
    // half already at full native resolution in the source file, so the whole frame must be
    // squeezed to the monitor's native width for its own hardware to unsqueeze per eye) - see
    // ImageScreen.kt's StereoImage. Persisted
    // (halveLeftRightImagesPref above) since it depends on the user's monitor, not the
    // current viewing session, and defaults to on to match the common Half-SBS setup.
    var halveLeftRightImages by mutableStateOf(halveLeftRightImagesPref.load())
        private set
    // Toggled from ImageScreen's settings menu: when true, every UI control overlay (the settings
    // menu icon/panel, the raw/edited label, the info panel, and every confirmation dialog) is
    // squeezed horizontally by 2 and, for dialogs, duplicated per half - the same treatment
    // halveLeftRightImages gives the photo itself. A Half-SBS 3D monitor's hardware unsqueezes the
    // whole frame per eye, so without this, controls that aren't part of the photo would read
    // stretched 2x wide - see ShrinkControls.kt. Persisted (shrinkControlsPref above) for the
    // same reason as halveLeftRightImages: it depends on the user's monitor, not the current
    // viewing session. Defaults to off since it's a new opt-in control, unlike
    // halveLeftRightImages which defaults on to match the common Half-SBS setup.
    var shrinkControls by mutableStateOf(shrinkControlsPref.load())
        private set
    // Set from ImageScreen's settings dialog (opened via the settings menu's "Settings" row): how
    // far one continuous arrow-key hold nudges the pending manual-align offset per second, as a
    // percentage of the eye-half's width (horizontal)/image height (vertical) instead of a fixed
    // pixel count - see
    // PhotoToolsState.manualAlignOffsetX/Y and Main.kt's tick loop, which multiplies this rate by
    // 10 once a direction key has been held 5s continuously (unchanged acceleration behavior).
    // Persisted (manualAlignStepPercentPref above) for the same reason as
    // halveLeftRightImages: it's a durable editing preference, not tied to the current session.
    var manualAlignStepPercent by mutableStateOf(manualAlignStepPercentPref.load())
        private set
    // Set from VideoScreen's settings menu ("Audio output" row): the libVLC mmdevice device id
    // video playback sends its audio to, or "" to follow the Windows default playback device.
    // Exists because libVLC follows the Windows default, and on the user's 3D setup (laptop with
    // its own screen disabled, the 3D monitor as sole display - required for 3D mode) Windows
    // makes the monitor's display-audio output the default, which has no audible speakers -
    // so a pinned device is the only way to get sound out of the laptop speakers there.
    // Persisted (audioOutputDeviceIdPref above) for the same reason as halveLeftRightImages:
    // it depends on the user's hardware, not the current viewing session.
    var audioOutputDeviceId by mutableStateOf(audioOutputDeviceIdPref.load())
        private set
    // Only set when imageFiles came from a playlist with isAutomated=true; drives the
    // auto-advance timer in Main.kt. Plain file selections never auto-advance.
    var isAutomatedPlaylist by mutableStateOf(false)
        private set
    var slideshowIntervalMs by mutableStateOf(10_000L)
        private set
    // True while an auto-align/correct-zoom/save task is running on a background thread - drives
    // the disabled state of AlignButtonsRow's three buttons so a task can't be re-triggered (or
    // overlap with another one) before it finishes.
    var isAligning by mutableStateOf(false)
        private set

    // Set by showNextImage/showPreviousImage instead of navigating immediately when an
    // auto-align/correct-zoom preview is pending (see photoTools.alignedPreview) - drives
    // ImageScreen's Save/Discard/Cancel dialog. Cleared by confirmSaveAndNavigate/
    // discardAlignedPreviewAndNavigate/cancelPendingNavigation.
    var pendingNavigation by mutableStateOf<PendingNavigationDirection?>(null)
        private set

    // The three mutually-exclusive per-photo edit tools (manual align, crop, spot stereo issues)
    // plus the pending auto-align/correct-zoom preview - see PhotoToolsState's own doc comment for
    // why this is a single object rather than nine separate mutableStateOf properties here.
    val photoTools = PhotoToolsState()

    // GalleryScreen's state - see GalleryState's own doc comment.
    val gallery = GalleryState()

    // The settings-menu "Share" dialog's state - see ShareState's own doc comment.
    val share = ShareState()

    // The PlaylistList/PlaylistEdit/PlaylistItem screens' state - see PlaylistEditorState's own doc comment.
    val playlistEditor = PlaylistEditorState()

    // Bumped on every auto-align/correct-zoom attempt (success or failure) so ImageScreen's toast
    // can (re)trigger even when the same outcome repeats back-to-back - see applyAlignedPreview.
    var alignToast by mutableStateOf<AlignToast?>(null)
        private set
    private var alignToastCounter = 0
    // Bumped on every "Save" attempt (success or failure) so ImageScreen's toast can (re)trigger
    // even when the same outcome repeats back-to-back - see performSaveAligned.
    var saveToast by mutableStateOf<SaveToast?>(null)
        private set
    private var saveToastCounter = 0

    val currentImage: File? get() = imageFiles.getOrNull(currentImageIndex)

    // imageFiles is built 1:1 from playingPlaylist.photos (see onPlaylistChosen/playPlaylist), so
    // currentImageIndex indexes directly into it while a playlist is playing; null for a plain
    // file selection or while on the title/end slide.
    val currentPlaylistItem: PlaylistItem? get() = playingPlaylist?.photos?.getOrNull(currentImageIndex)

    // The playlist currently playing in ImageView (title/photos/end slides), null when ImageView
    // shows a plain file selection instead - mirrors CameraSync3D's SlideshowViewModel.playlist.
    var playingPlaylist by mutableStateOf<Playlist?>(null)
        private set

    // currentImageIndex ranges over -1 (title slide) .. imageFiles.size (end slide) while a
    // playlist is playing, and 0..imageFiles.lastIndex for a plain file selection.
    val playlistSlideKind: PlaylistSlideKind?
        get() = playingPlaylist?.let {
            when {
                currentImageIndex < 0 -> PlaylistSlideKind.TITLE
                currentImageIndex >= imageFiles.size -> PlaylistSlideKind.END
                else -> PlaylistSlideKind.PHOTO
            }
        }

    // Index of the slide the playlist credits roll on (the last photo, or the end slide = imageFiles.size),
    // see isCreditsRolling - mirrors CameraSync3D's SlideshowViewModel.isCreditsRolling.
    private var creditsSlideIndex by mutableStateOf<Int?>(null)

    /** True while the playlist credits roll, over the last photo or on the end slide (see Playlist.creditsPlacement). */
    val isCreditsRolling: Boolean
        get() = playingPlaylist != null && creditsSlideIndex == currentImageIndex

    fun onLanguageChosen(languageTag: String?) {
        language = languageTag
    }

    fun onUseNewOpenCv5Chosen(value: Boolean) {
        useNewOpenCv5 = value
        useNewOpenCv5Pref.save(value)
    }

    fun onKeepBestOfEachOnlyChosen(value: Boolean) {
        keepBestOfEachOnly = value
        keepBestOfEachOnlyPref.save(value)
        if (value) snapToVisiblePhoto()
    }

    fun onFavoritesOnlyChosen(value: Boolean) {
        favoritesOnly = value
        favoritesOnlyPref.save(value)
        if (value) snapToVisiblePhoto()
    }

    fun onExcludeStereoIssuesChosen(value: Boolean) {
        excludeStereoIssues = value
        excludeStereoIssuesPref.save(value)
        if (value) snapToVisiblePhoto()
    }

    fun onHalveLeftRightImagesChosen(value: Boolean) {
        halveLeftRightImages = value
        halveLeftRightImagesPref.save(value)
    }

    fun onShrinkControlsChosen(value: Boolean) {
        shrinkControls = value
        shrinkControlsPref.save(value)
    }

    fun onManualAlignStepPercentChosen(value: Float) {
        val clamped = value.coerceIn(MinManualAlignStepPercent, MaxManualAlignStepPercent)
        manualAlignStepPercent = clamped
        manualAlignStepPercentPref.save(clamped)
    }

    fun onAudioOutputDeviceChosen(deviceId: String) {
        audioOutputDeviceId = deviceId
        audioOutputDeviceIdPref.save(deviceId)
    }

    /**
     * Whether any of keepBestOfEachOnly/favoritesOnly/excludeStereoIssues is currently on - always
     * false while a playlist plays, since a playlist is a curated list whose items must all be shown
     * (the filters only narrow down a plain folder/file selection).
     */
    private val anyPhotoFilterActive: Boolean
        get() = playingPlaylist == null && (keepBestOfEachOnly || favoritesOnly || excludeStereoIssues)

    /**
     * The subset of [files] that passes every currently-active filter (keepBestOfEachOnly/
     * favoritesOnly/excludeStereoIssues combined with AND) - used by showNextImage/
     * showPreviousImage/advanceSlideshow/snapToVisiblePhoto to skip filtered-out photos.
     */
    private fun visiblePhotos(files: List<File>): Set<File> {
        var visible: Set<File> = files.toSet()
        if (keepBestOfEachOnly) visible = visible intersect bestVersionsOnly(files)
        if (favoritesOnly) visible = visible.filterTo(mutableSetOf(), Exif3d::getFavoriteFromExif)
        if (excludeStereoIssues) visible = visible.filterTo(mutableSetOf()) {
            !Exif3d.getWarningFromExif(it) && !rawEditedLabel(it).startsWith("stereo_issues")
        }
        return visible
    }

    /**
     * If the currently shown photo doesn't pass every active filter, jumps to the nearest one that
     * does (forward first, then backward) so turning a filter on never leaves a filtered-out photo
     * on screen.
     */
    private fun snapToVisiblePhoto() {
        val index = currentImageIndex
        if (index !in imageFiles.indices || !anyPhotoFilterActive) return
        val visible = visiblePhotos(imageFiles)
        if (imageFiles[index] in visible) return
        val target = (index + 1..imageFiles.lastIndex).firstOrNull { imageFiles[it] in visible }
            ?: (index - 1 downTo 0).firstOrNull { imageFiles[it] in visible }
        if (target != null) {
            currentImageIndex = target
            photoTools.resetAll()
        }
    }

    /** Opens [files] in the viewer; returns false (raising the VLC-missing sheet) if the first is a video and VLC is absent. */
    fun onFilesChosen(files: List<File>): Boolean {
        if (files.firstOrNull()?.extension?.lowercase() in videoExtensions && !requireVlc()) return false
        playingPlaylist = null
        imageFiles = files
        currentImageIndex = 0
        isAutomatedPlaylist = false
        photoTools.resetAll()
        if (anyPhotoFilterActive) snapToVisiblePhoto()
        screen = if (files.firstOrNull()?.extension?.lowercase() in videoExtensions) {
            Screen.VideoView
        } else {
            Screen.ImageView
        }
        return true
    }

    /** Recursively scans [folder] for images and switches to the Gallery screen. */
    fun openGallery(folder: File) {
        gallery.open(folder)
        screen = Screen.Gallery
        requireVlcIfGalleryHasVideo()
    }

    /**
     * Whether the VLC-missing bottom sheet is showing. VLC isn't bundled: videos and playlist music
     * play through the user's own install (see Vlc.kt), so we warn when one of those is about to be used.
     */
    var showVlcMissingSheet by mutableStateOf(false)
        private set

    /** Checks for VLC and, if absent, raises [showVlcMissingSheet]. Returns whether VLC is installed. */
    fun requireVlc(): Boolean {
        val installed = Vlc.isInstalled()
        if (!installed && !showVlcMissingSheet) {
            showVlcMissingSheet = true
            Analytics.logEvent("app_error", mapOf("type" to "vlc_missing"))
        }
        return installed
    }

    fun dismissVlcMissingSheet() {
        showVlcMissingSheet = false
    }

    init {
        // A video launched via file association without VLC starts on Welcome (see [screen]'s initializer).
        if (initialFile != null && initialFile.extension.lowercase() in videoExtensions) requireVlc()
    }

    private fun requireVlcIfGalleryHasVideo() {
        if (gallery.groups.any { group -> group.files.any { isVideoFilename(it.name) } }) requireVlc()
    }

    fun closeGallery() {
        gallery.close()
        screen = Screen.Welcome
    }

    /** Opens the Gallery in multi-select picker mode, for PlaylistEdit's "+" button. */
    fun openGalleryForPicking(folder: File) {
        gallery.open(folder, pickerMode = true)
        screen = Screen.Gallery
        requireVlcIfGalleryHasVideo()
    }

    fun toggleGallerySelection(file: File) {
        gallery.toggleSelection(file)
    }

    /** Cancels the Gallery picker without adding anything, returning to the playlist being edited. */
    fun cancelGalleryPicker() {
        gallery.close()
        screen = Screen.PlaylistEdit
    }

    /** Confirms the Gallery picker's selection: adds the picked photos to the playlist being edited. */
    fun confirmGalleryPicker() {
        val files = gallery.selectedFiles.toList()
        gallery.close()
        if (files.isNotEmpty()) addPhotosToEditingPlaylist(files)
        screen = Screen.PlaylistEdit
    }

    fun openAbout() {
        screen = Screen.About
    }

    fun closeAbout() {
        screen = Screen.Welcome
    }

    fun consumeGalleryScrollTarget() {
        gallery.consumeScrollTarget()
    }

    fun toggleGalleryGroup(relativePath: String) {
        gallery.toggleGroup(relativePath)
    }

    /**
     * Opens [group]'s file at [index] fullscreen; Left/Right then navigate the same-type files
     * (photos or videos) within that group only - a mixed group's photos and videos aren't
     * interleaved into one navigation session, since ImageView/VideoView are two different
     * screens the raw file browsing flow doesn't switch between mid-session (unlike a playlist
     * slideshow, which stays on Screen.ImageView throughout and branches per playlistItem.isVideo).
     */
    fun openGalleryImage(group: GalleryGroup, index: Int): Boolean {
        val tapped = group.files.getOrNull(index) ?: return false
        val isVideo = isVideoFilename(tapped.name)
        if (isVideo && !requireVlc()) return false
        val sameTypeFiles = group.files.filter { isVideoFilename(it.name) == isVideo }
        playingPlaylist = null
        imageFiles = sameTypeFiles
        currentImageIndex = sameTypeFiles.indexOf(tapped)
        isAutomatedPlaylist = false
        photoTools.resetAll()
        if (!isVideo && anyPhotoFilterActive) snapToVisiblePhoto()
        gallery.markEntered()
        screen = if (isVideo) Screen.VideoView else Screen.ImageView
        return true
    }

    fun onPlaylistChosen(playlist: Playlist, files: List<File>, isAutomated: Boolean, intervalMs: Long) {
        playingPlaylist = playlist
        imageFiles = files
        currentImageIndex = -1 // start on the title slide
        creditsSlideIndex = null
        isAutomatedPlaylist = isAutomated
        slideshowIntervalMs = intervalMs
        photoTools.resetAll()
        screen = Screen.ImageView
    }

    /** Discards the pending auto-align/correct-zoom preview without touching disk - same shape as
     *  [cancelManualAlign]/[cancelCrop]/[cancelSpotIssues]/[cancelClickAlign]. */
    fun cancelAligned() {
        photoTools.cancelAlignedPreview()
    }

    /** Applies a finished auto-align/correct-zoom attempt's result and (re)triggers [alignToast]. */
    fun applyAlignedPreview(result: AutoAlign.AutoAlignResult?, kind: AlignKind? = null) {
        photoTools.applyAlignedPreview(result, kind)
        alignToastCounter++
        alignToast = AlignToast(
            success = result != null,
            token = alignToastCounter,
            zoomScale = result?.zoomScale,
            rotationDegrees = result?.rotationDegrees,
        )
    }

    /**
     * Whether the per-photo edit tools (align/crop/spot issues/delete) may run: not while a
     * playlist plays, since each of them inserts into or removes from [imageFiles], which would
     * desync it from playingPlaylist.photos (and a delete would leave the playlist's index
     * pointing at a missing file) - see ImageScreen's showEditTools.
     */
    private val canEditPhotos: Boolean get() = !isAligning && playingPlaylist == null

    /** Enters manual-align mode for the currently shown photo - see Main.kt's arrow-key handling. */
    fun startManualAlign() {
        if (!canEditPhotos) return
        photoTools.startManualAlign()
    }

    /** Enters crop mode for the currently shown photo - see Exif3dInfoPanel's Crop button. */
    fun startCrop() {
        if (!canEditPhotos) return
        photoTools.startCrop()
    }

    /** Records the rectangle drawn in ImageScreen's onCropDragEnd, switching to the review phase. */
    fun finalizeCropRect(rect: CropRectFraction) {
        photoTools.finalizeCropRect(rect)
    }

    /** Discards the crop tool (drawn rectangle or not) without touching disk. */
    fun cancelCrop() {
        photoTools.cancelCrop()
    }

    /**
     * Writes the pending crop rectangle to disk via [Crop.saveCrop] (which reuses
     * [AutoAlign.writeAlignedResult], the same file-naming/EXIF-copy step every save path uses)
     * and, on success, inserts the new file right after the current one and jumps to it -
     * identical treatment to [performSaveManualAlign].
     */
    suspend fun performSaveCrop() {
        if (isAligning || !photoTools.cropMode) return
        val file = currentImage
        val rect = photoTools.cropRect ?: return
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { Crop.saveCrop(file, rect) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, exifError = saved?.exifError, token = saveToastCounter)
            photoTools.cancelCrop()
            if (saved == null) return
            Analytics.logEvent("crop_save")
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved.file) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Enters "spot stereo issues" mode for the currently shown photo - see Exif3dInfoPanel's button. */
    fun startSpotIssues() {
        if (!canEditPhotos) return
        photoTools.startSpotIssues()
    }

    /** Appends one rectangle drawn in ImageScreen's onSpotIssueDragEnd - the tool stays active so
     *  further rectangles can be drawn, unlike [finalizeCropRect]'s single-rectangle review phase. */
    fun addSpotIssueRect(rect: IssueRectFraction) {
        photoTools.addSpotIssueRect(rect)
    }

    /** Discards the "spot stereo issues" tool (drawn rectangles or not) without touching disk. */
    fun cancelSpotIssues() {
        photoTools.cancelSpotIssues()
    }

    /**
     * Writes the pending rectangles to disk via [SpotStereoIssues.saveSpotIssues] (which reuses
     * [AutoAlign.writeAlignedResult], the same file-naming/EXIF-copy step every save path uses)
     * and, on success, inserts the new file right after the current one and jumps to it -
     * identical treatment to [performSaveCrop].
     */
    suspend fun performSaveSpotIssues() {
        if (isAligning || !photoTools.spotIssuesMode) return
        val file = currentImage
        val rects = photoTools.spotIssueRects
        if (rects.isEmpty()) return
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { SpotStereoIssues.saveSpotIssues(file, rects) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, exifError = saved?.exifError, token = saveToastCounter)
            photoTools.cancelSpotIssues()
            if (saved == null) return
            Analytics.logEvent("spot_issues_save")
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved.file) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Adds a fractional delta to the pending manual-align offset - see Main.kt's tick loop. */
    fun nudgeManualAlign(dx: Float, dy: Float) {
        photoTools.nudgeManualAlign(dx, dy)
    }

    /** Discards the pending manual-align offset without touching disk. */
    fun cancelManualAlign() {
        photoTools.cancelManualAlign()
    }

    /**
     * Writes the pending manual-align offset to disk via [ManualAlign.saveManualAlign] (which
     * reuses [AutoAlign.writeAlignedResult], the same file-naming/EXIF-copy step performSaveAligned
     * uses) and, on success, inserts the new file right after the current one and jumps to it -
     * identical treatment to [performSaveAligned].
     */
    suspend fun performSaveManualAlign() {
        if (isAligning || !photoTools.manualAlignMode) return
        val file = currentImage
        val dx = photoTools.manualAlignOffsetX
        val dy = photoTools.manualAlignOffsetY
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { ManualAlign.saveManualAlign(file, dx, dy) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, exifError = saved?.exifError, token = saveToastCounter)
            photoTools.cancelManualAlign()
            if (saved == null) return
            Analytics.logEvent("manual_align_save")
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved.file) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Enters "click matching points" mode for the currently shown photo - see AlignButtonsRow's "Manual Align" submenu. */
    fun startClickAlign() {
        if (!canEditPhotos) return
        photoTools.startClickAlign()
    }

    /** Records/moves the point for whichever eye-half was clicked - see ImageScreen's onRawClick wiring. */
    fun setClickAlignPoint(isLeft: Boolean, point: PointFraction) {
        photoTools.setClickAlignPoint(isLeft, point)
    }

    /** Discards the click-align tool (points placed or not) without touching disk. */
    fun cancelClickAlign() {
        photoTools.cancelClickAlign()
    }

    /** Toggled by Main.kt's onPreviewKeyEvent while Shift is held during clickAlignMode. */
    fun setClickAlignPreviewActive(active: Boolean) {
        photoTools.updateClickAlignPreviewActive(active)
    }

    /**
     * (Re)computes the Shift-held preview (see ClickAlign.Preview and ImageScreen's LaunchedEffect
     * that calls this) - run off the UI thread since it re-decodes the full-resolution file, same as
     * every other align/crop/save call.
     */
    suspend fun refreshClickAlignPreview() {
        val file = currentImage
        val left = photoTools.clickAlignLeftPoint
        val right = photoTools.clickAlignRightPoint
        if (file == null || left == null || right == null) return
        val preview = withContext(Dispatchers.IO) { ClickAlign.computePreview(file, left, right, halveLeftRightImages) }
        photoTools.applyClickAlignPreview(preview)
    }

    /**
     * Writes the two clicked points to disk via [ClickAlign.saveClickAlign] (which delegates to
     * [ManualAlign.saveManualAlign], the same file-naming/EXIF-copy step every save path uses) and,
     * on success, inserts the new file right after the current one and jumps to it - identical
     * treatment to [performSaveManualAlign].
     */
    suspend fun performSaveClickAlign() {
        if (isAligning || !photoTools.clickAlignMode) return
        val file = currentImage
        val left = photoTools.clickAlignLeftPoint
        val right = photoTools.clickAlignRightPoint
        if (left == null || right == null) return
        isAligning = true
        try {
            val saved = if (file != null) {
                withContext(Dispatchers.IO) { ClickAlign.saveClickAlign(file, left, right) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, exifError = saved?.exifError, token = saveToastCounter)
            photoTools.cancelClickAlign()
            if (saved == null) return
            Analytics.logEvent("click_align_save")
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved.file) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /**
     * Runs auto-align/correct-zoom for [file] on a background thread (rather than blocking the UI
     * thread like the old synchronous call did) and applies the result when done. [isAligning]
     * is true for the whole duration so AlignButtonsRow's buttons can disable themselves and avoid
     * a second task overlapping this one.
     */
    suspend fun performAutoAlign(file: File, kind: AlignKind) {
        if (!canEditPhotos) return
        isAligning = true
        try {
            val result = withContext(Dispatchers.IO) { AutoAlign.autoAlign(file, kind, useNewOpenCv5) }
            applyAlignedPreview(result, kind)
        } finally {
            isAligning = false
        }
    }

    /**
     * Redoes the pending align against the original file (not the in-memory preview, to avoid
     * re-compressing an already-lossy preview - mirrors CameraSync3D's save flow, which reapplies
     * the pending align to the original at save time instead of re-encoding the cached preview)
     * and writes it to disk under a new filename, then inserts the new file right after the
     * current one and jumps to it. Runs on a background thread and keeps [isAligning] true for the
     * duration, same treatment as [performAutoAlign].
     */
    suspend fun performSaveAligned() {
        if (isAligning) return
        val file = currentImage
        val kind = photoTools.pendingAlignKind
        isAligning = true
        try {
            val saved = if (file != null && kind != null) {
                withContext(Dispatchers.IO) { AutoAlign.saveAligned(file, kind, useNewOpenCv5) }
            } else null
            saveToastCounter++
            saveToast = SaveToast(success = saved != null, exifError = saved?.exifError, token = saveToastCounter)
            if (saved == null) return
            kind?.let {
                val mode = if (it == AlignKind.HOMOGRAPHY) "auto_align" else "correct_zoom_issues"
                Analytics.logEvent("align_save", mapOf("mode" to mode))
            }
            photoTools.resetAll()
            val insertAt = currentImageIndex + 1
            imageFiles = imageFiles.toMutableList().apply { add(insertAt, saved.file) }
            currentImageIndex = insertAt
        } finally {
            isAligning = false
        }
    }

    /** Which eye-half to keep as a standalone 2D photo when deleting an unedited stereo pair - see [performDeleteCurrentImage]. */
    enum class KeepHalfSide { LEFT, RIGHT }

    /**
     * Deletes the currently shown photo from disk. With [keepHalf] set (InfoPanel's delete tool
     * offers this only for an unedited "_raw" photo - see GalleryScreen.kt's rawEditedLabel), that
     * eye-half is first saved as a standalone 2D JPEG via [KeepHalf.saveHalf] (same file-naming/
     * EXIF-copy step every save path uses) and swapped into the current slot, triggering [saveToast]
     * like performSaveCrop/performSaveManualAlign/etc.; with [keepHalf] null the photo is simply
     * removed from [imageFiles] and disk with no replacement - same shape as playlistEditor's
     * deleteItem - landing on whichever photo is now at the same index (the previous one if the
     * deletion emptied the tail), or closing the viewer if the list becomes empty. No toast for
     * this path: the photo disappearing from the list is its own feedback, and a failed disk
     * delete (e.g. a locked file) simply leaves the photo in place rather than desyncing the list
     * from disk.
     */
    suspend fun performDeleteCurrentImage(keepHalf: KeepHalfSide? = null) {
        if (!canEditPhotos) return
        val file = currentImage ?: return
        isAligning = true
        try {
            if (keepHalf != null) {
                val saved = withContext(Dispatchers.IO) { KeepHalf.saveHalf(file, keepHalf == KeepHalfSide.LEFT) }
                saveToastCounter++
                saveToast = SaveToast(success = saved != null, exifError = saved?.exifError, token = saveToastCounter)
                if (saved == null) return
                withContext(Dispatchers.IO) { file.delete() }
                imageFiles = imageFiles.toMutableList().apply { this[currentImageIndex] = saved.file }
                photoTools.resetAll()
                Analytics.logEvent("photo_delete", mapOf("keep_half" to keepHalf.name.lowercase()))
            } else {
                val deleted = withContext(Dispatchers.IO) { file.delete() }
                if (!deleted) return
                val newFiles = imageFiles.toMutableList().apply { removeAt(currentImageIndex) }
                imageFiles = newFiles
                if (newFiles.isEmpty()) {
                    closeImageView()
                } else {
                    currentImageIndex = currentImageIndex.coerceAtMost(newFiles.lastIndex)
                    photoTools.resetAll()
                }
                Analytics.logEvent("photo_delete", mapOf("keep_half" to "none"))
            }
        } finally {
            isAligning = false
        }
    }

    /**
     * Prepares the currently shown photo and hands it off per [type]/[destination] via
     * [ShareState.perform] - see that class's doc. A Downloads save reuses [saveToast]
     * (the same success/failure flash as every other "Save" action in this app), so it's reported
     * back here rather than owned by ShareState.
     */
    suspend fun performShare(type: Share.ShareType, destination: Share.Destination) {
        val file = currentImage ?: return
        share.perform(file, type, destination) { success ->
            saveToastCounter++
            saveToast = SaveToast(success = success, token = saveToastCounter)
        }
    }

    /** Scans playlistsRoot for playlist folders and switches to the PlaylistList screen. */
    fun openPlaylistList() {
        playlistEditor.openList()
        screen = Screen.PlaylistList
    }

    /** Re-scans playlistsRoot without leaving the PlaylistList screen. */
    fun refreshPlaylistList() {
        playlistEditor.refreshList()
    }

    fun closePlaylistList() {
        screen = Screen.Welcome
    }

    /** Opens the given playlist (picked from the list screen) in the editor. */
    fun openPlaylistForEdit(playlist: Playlist) {
        playlistEditor.openForEdit(playlist)
        screen = Screen.PlaylistEdit
        requireVlcIfPlaylistHasVideo(playlist)
    }

    private fun requireVlcIfPlaylistHasVideo(playlist: Playlist) {
        if (playlist.soundtrack.isNotEmpty() || playlist.photos.any { it.isVideo }) requireVlc()
    }

    /** Starts the slideshow directly for the given playlist (picked from the list screen). */
    fun playPlaylist(playlist: Playlist): Boolean {
        if (!canPlayPlaylist(playlist)) return false
        playlistEditor.closeEdit()
        playlistEditor.markEnteredFromList()
        val files = playlist.photos.map { playlistItemFile(it.imageUriString) }
        onPlaylistChosen(playlist, files, playlist.isAutomated, playlist.defaultDurationS * 1000)
        return true
    }

    /**
     * Whether [playlist] can start: false (and logged) if it holds a video or a soundtrack while VLC
     * is missing, in which case the calling screen toasts instead of starting the slideshow.
     */
    private fun canPlayPlaylist(playlist: Playlist): Boolean {
        val needsVlc = playlist.soundtrack.isNotEmpty() || playlist.photos.any { it.isVideo }
        if (!needsVlc || Vlc.isInstalled()) return true
        Analytics.logEvent("app_error", mapOf("type" to "vlc_missing"))
        return false
    }

    /**
     * Copies an externally-selected playlist folder into playlistsRoot and opens it for editing -
     * see PlaylistEditorState.importFolder. Returns false without copying anything if a playlist
     * with the same folder name already exists in the root.
     */
    fun importPlaylistFolder(folder: File): Boolean {
        val imported = playlistEditor.importFolder(folder) ?: return false
        screen = Screen.PlaylistEdit
        requireVlcIfPlaylistHasVideo(imported)
        return true
    }

    /** Whether a new playlist named [name] wouldn't collide with an existing one already on disk. */
    fun canCreatePlaylist(name: String): Boolean = playlistEditor.canCreate(name)

    /** Creates a playlist folder under Pictures/SBS 3D Viewer/{name} and switches to the
     *  PlaylistEdit screen for it - see PlaylistEditorState.startCreate. No-ops on a blank name. */
    fun startCreatePlaylist(name: String) {
        if (!playlistEditor.startCreate(name)) return
        screen = Screen.PlaylistEdit
    }

    /** Copies the given files (chosen directly via a multi-select file dialog) into the playlist being edited and appends them to its index. */
    fun addPhotosToEditingPlaylist(files: List<File>) {
        if (files.any { isVideoFilename(it.name) }) requireVlc()
        playlistEditor.addPhotos(files)
    }

    /** Starts the slideshow for the playlist currently open in the PlaylistEdit screen. */
    fun playEditingPlaylist(): Boolean {
        val playlist = playlistEditor.editing ?: return false
        if (!canPlayPlaylist(playlist)) return false
        onPlaylistChosen(playlist, playlistEditor.editingPhotoFiles(), playlist.isAutomated, playlist.defaultDurationS * 1000)
        return true
    }

    /** Whether newName's folder doesn't collide with another playlist already on disk. */
    fun canRenamePlaylist(newName: String): Boolean = playlistEditor.canRename(newName)

    /** Renames the playlist being edited: moves its folder on disk and updates the index. */
    fun modifyPlaylistName(newName: String): Boolean = playlistEditor.rename(newName)

    /** Updates the slideshow default duration between slides, in seconds (1..60, like CameraSync3D). */
    fun modifyDefaultDuration(newDurationS: Long): Boolean = playlistEditor.modifyDefaultDuration(newDurationS)

    fun modifyIsAutomated(newValue: Boolean): Boolean = playlistEditor.modifyIsAutomated(newValue)

    fun modifyScrollSpeedLimitPercent(newValue: Float): Boolean = playlistEditor.modifyScrollSpeedLimitPercent(newValue)

    fun modifySubtitle(newValue: String): Boolean = playlistEditor.modifySubtitle(newValue)

    fun modifyTitleZPercent(newValue: Float): Boolean = playlistEditor.modifyTitleZPercent(newValue)

    fun modifySubtitleZPercent(newValue: Float): Boolean = playlistEditor.modifySubtitleZPercent(newValue)

    fun modifyTitleStyle(newValue: TextStyleConfig): Boolean = playlistEditor.modifyTitleStyle(newValue)

    fun modifySubtitleStyle(newValue: TextStyleConfig): Boolean = playlistEditor.modifySubtitleStyle(newValue)

    fun modifyCredits(newValue: String): Boolean = playlistEditor.modifyCredits(newValue)

    fun modifyCreditsZPercent(newValue: Float): Boolean = playlistEditor.modifyCreditsZPercent(newValue)

    fun modifyCreditsStyle(newValue: TextStyleConfig): Boolean = playlistEditor.modifyCreditsStyle(newValue)

    fun modifyCreditsPlacement(newValue: CreditsPlacement): Boolean = playlistEditor.modifyCreditsPlacement(newValue)

    /** Applies a fully-reordered photo list (e.g. after drag-and-drop) and saves to disk. */
    fun applyPhotosReorder(newPhotos: List<PlaylistItem>) {
        playlistEditor.applyPhotosReorder(newPhotos)
    }

    /** Copies the chosen audio files into the playlist being edited as its soundtrack - returns how many were added. */
    fun addSoundtrackToEditingPlaylist(files: List<File>): Int {
        if (files.isNotEmpty()) requireVlc()
        return playlistEditor.addSoundtrack(files)
    }

    fun removeSoundtrackItem(index: Int) {
        playlistEditor.removeSoundtrackItem(index)
    }

    /** Opens the given photo (picked from the PlaylistEdit screen's list) in the PlaylistItem screen. */
    fun openPlaylistItem(index: Int) {
        playlistEditor.openItem(index)
        screen = Screen.PlaylistItem
    }

    fun closePlaylistItem() {
        playlistEditor.closeItem()
        screen = Screen.PlaylistEdit
    }

    fun modifyItemComment(newValue: String): Boolean = playlistEditor.modifyItemComment(newValue)

    fun modifyItemCommentZPercent(newValue: Float): Boolean = playlistEditor.modifyItemCommentZPercent(newValue)

    fun modifyItemDuration(newValue: Int): Boolean = playlistEditor.modifyItemDuration(newValue)

    fun modifyItemHalfWidth(newValue: Boolean) {
        playlistEditor.modifyItemHalfWidth(newValue)
    }

    fun modifyItemMuted(newValue: Boolean) {
        playlistEditor.modifyItemMuted(newValue)
    }

    fun modifyItemFullscreenScroll(newValue: FullscreenScroll) {
        playlistEditor.modifyItemFullscreenScroll(newValue)
    }

    /** Deletes the photo open in the PlaylistItem screen: removes it from the playlist and from disk. */
    fun deletePlaylistItem(): Boolean = playlistEditor.deleteItem()

    /** Deletes the playlist being edited (folder and all) from disk. */
    fun deletePlaylist(): Boolean = playlistEditor.delete()

    fun closePlaylistEdit() {
        playlistEditor.closeEdit()
        screen = playlistEditor.returnFromChildScreen()
    }

    /**
     * Drops every in-progress screen/tool state and lands back on Welcome - called by Main.kt's
     * window exception handler after a UI crash, since whatever state was being rendered when it
     * threw may well throw again if re-shown as-is.
     */
    fun resetAfterCrash() {
        playingPlaylist = null
        pendingNavigation = null
        alignToast = null
        saveToast = null
        share.clearToast()
        photoTools.resetAll()
        gallery.close()
        playlistEditor.closeItem()
        playlistEditor.closeEdit()
        screen = Screen.Welcome
    }

    fun closeImageView() {
        // A slideshow started from PlaylistEdit's "Play" button returns there instead of Welcome/PlaylistList.
        playingPlaylist = null
        // Otherwise the stale toast re-flashes on next open: leaving ImageView recreates the whole
        // Window (see Main.kt's key(undecorated)), which resets StereoToast's remembered visibility
        // but not this still-non-null trigger, so its LaunchedEffect fires again on first composition.
        alignToast = null
        saveToast = null
        share.clearToast()
        screen = when {
            playlistEditor.editing != null -> Screen.PlaylistEdit
            gallery.enteredFromGallery -> {
                gallery.markReturnedTo(currentImage)
                Screen.Gallery
            }
            else -> playlistEditor.returnFromChildScreen()
        }
    }

    /**
     * Advances to the next photo, or - while playing a playlist - into/out of the title and end
     * slides: TITLE -> first photo -> ... -> last photo -> END -> (wraps back to) TITLE, mirroring
     * CameraSync3D's SlideshowViewModel.nextPhoto()/play() "relaunch" behavior.
     */
    /** Navigates forward, or asks first (see [pendingNavigation]) if an auto-align/correct-zoom
     *  preview is pending for the current photo - it would otherwise be silently discarded by
     *  [navigateNext]'s [PhotoToolsState.resetAll] call. */
    fun showNextImage() {
        if (photoTools.alignedPreview != null) {
            pendingNavigation = PendingNavigationDirection.NEXT
            return
        }
        navigateNext()
    }

    /** Same guard as [showNextImage], for the Previous direction. */
    fun showPreviousImage() {
        if (photoTools.alignedPreview != null) {
            pendingNavigation = PendingNavigationDirection.PREVIOUS
            return
        }
        navigatePrevious()
    }

    /** Logs one "picture viewed" event per photo landed on while a playlist is playing (title/end
     *  slides don't count) - shared by navigateNext/navigatePrevious/advanceSlideshow so manual
     *  navigation and the automated timer are counted the same way. */
    private fun trackPlaylistPhotoViewedIfApplicable() {
        if (playingPlaylist != null && playlistSlideKind == PlaylistSlideKind.PHOTO) {
            Analytics.logEvent("playlist_photo_viewed")
        }
    }

    private fun navigateNext() {
        // Space/Right while the credits roll skips them (like a tap on CameraSync3D)
        if (isCreditsRolling) {
            onCreditsEnded()
            return
        }
        val upperBound = if (playingPlaylist != null) imageFiles.size else imageFiles.lastIndex
        if (currentImageIndex >= upperBound) {
            if (playingPlaylist != null && currentImageIndex == imageFiles.size) {
                currentImageIndex = -1 // replay: END -> TITLE
                photoTools.resetAll()
            }
            return
        }
        var nextIndex = currentImageIndex + 1
        if (anyPhotoFilterActive) {
            val visible = visiblePhotos(imageFiles)
            while (nextIndex < upperBound && imageFiles[nextIndex] !in visible) nextIndex++
            // Nothing further to skip to before the boundary (last photo / end slide) - stay put
            // rather than land on a filtered-out photo, which is what let raw/edited pairs both
            // stay browsable at the end of a list.
            if (nextIndex < imageFiles.size && imageFiles[nextIndex] !in visible) return
        }
        if (startLastImageCredits(nextIndex)) return
        currentImageIndex = nextIndex
        startEndSlideCreditsIfPlaced()
        photoTools.resetAll()
        trackPlaylistPhotoViewedIfApplicable()
    }

    private fun navigatePrevious() {
        val lowerBound = if (playingPlaylist != null) -1 else 0
        if (currentImageIndex <= lowerBound) return
        var previousIndex = currentImageIndex - 1
        if (anyPhotoFilterActive) {
            val visible = visiblePhotos(imageFiles)
            while (previousIndex > lowerBound && imageFiles[previousIndex] !in visible) previousIndex--
            if (previousIndex >= 0 && imageFiles[previousIndex] !in visible) return
        }
        currentImageIndex = previousIndex
        creditsSlideIndex = null
        photoTools.resetAll()
        trackPlaylistPhotoViewedIfApplicable()
    }

    /** Dismisses [pendingNavigation]'s dialog without navigating or touching the pending preview. */
    fun cancelPendingNavigation() {
        pendingNavigation = null
    }

    /** Discards the pending auto-align/correct-zoom preview (without writing it to disk) and
     *  carries out the navigation that was waiting on [pendingNavigation]. */
    fun discardAlignedPreviewAndNavigate() {
        val direction = pendingNavigation ?: return
        pendingNavigation = null
        photoTools.resetAll()
        when (direction) {
            PendingNavigationDirection.NEXT -> navigateNext()
            PendingNavigationDirection.PREVIOUS -> navigatePrevious()
        }
    }

    /** Writes the pending auto-align/correct-zoom preview to disk (see [performSaveAligned]) and,
     *  only if that succeeds, carries out the navigation that was waiting on [pendingNavigation] -
     *  a failed save leaves the preview and the dialog's trigger in place so the user can retry. */
    suspend fun confirmSaveAlignedAndNavigate() {
        val direction = pendingNavigation ?: return
        performSaveAligned()
        if (photoTools.alignedPreview != null) return // save failed - stay put, let saveToast report it
        pendingNavigation = null
        when (direction) {
            PendingNavigationDirection.NEXT -> navigateNext()
            PendingNavigationDirection.PREVIOUS -> navigatePrevious()
        }
    }

    /** Advances to the next photo (or the end slide) - used by the playlist auto-advance timer only. */
    fun advanceSlideshow() {
        if (imageFiles.isEmpty() || currentImageIndex >= imageFiles.size) return
        var nextIndex = currentImageIndex + 1
        if (anyPhotoFilterActive) {
            val visible = visiblePhotos(imageFiles)
            while (nextIndex < imageFiles.size && imageFiles[nextIndex] !in visible) nextIndex++
        }
        // on the last photo, the credits roll before going to the end slide; onCreditsEnded() moves on
        if (isCreditsRolling || startLastImageCredits(nextIndex)) return
        currentImageIndex = nextIndex
        startEndSlideCreditsIfPlaced()
        photoTools.resetAll()
        trackPlaylistPhotoViewedIfApplicable()
    }

    /**
     * Starts rolling the credits over the current photo instead of moving on to [nextIndex], if that's the end
     * slide and the credits are placed over the last image.
     * @return true if the credits started rolling
     */
    private fun startLastImageCredits(nextIndex: Int): Boolean {
        val playlist = playingPlaylist ?: return false
        if (nextIndex < imageFiles.size || playlistSlideKind != PlaylistSlideKind.PHOTO || !playlist.hasCreditsOnLastImage()) return false
        creditsSlideIndex = currentImageIndex
        return true
    }

    /** Called once a slide is reached going forward: on the end slide, its credits (if placed there) roll before its "the end" animation. */
    private fun startEndSlideCreditsIfPlaced() {
        creditsSlideIndex = currentImageIndex.takeIf {
            playlistSlideKind == PlaylistSlideKind.END && playingPlaylist?.hasCreditsOnEndSlide() == true
        }
    }

    /**
     * Called once the credits have completely rolled, or are skipped: over the last photo, moves on to the end
     * slide; on the end slide, lets its "the end" animation (and the soundtrack fade-out) start.
     */
    fun onCreditsEnded() {
        if (!isCreditsRolling) return
        creditsSlideIndex = null
        if (playlistSlideKind == PlaylistSlideKind.PHOTO) {
            currentImageIndex = imageFiles.size
            photoTools.resetAll()
        }
    }
}
