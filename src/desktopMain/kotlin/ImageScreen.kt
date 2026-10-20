import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.shared.SideBySideLikeliness
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.align_save_button
import sbs3dViewer.resources.cancel_button
import sbs3dViewer.resources.discard_button
import sbs3dViewer.resources.image_decode_failed_message
import sbs3dViewer.resources.image_settings_exclude_stereo_issues_toggle_label
import sbs3dViewer.resources.image_settings_exit_fullscreen_label
import sbs3dViewer.resources.image_settings_favorites_only_toggle_label
import sbs3dViewer.resources.image_settings_halve_left_right_toggle_label
import sbs3dViewer.resources.image_settings_info_panel_label
import sbs3dViewer.resources.image_settings_keep_best_of_each_toggle_label
import sbs3dViewer.resources.image_settings_manual_align_step_label
import sbs3dViewer.resources.image_settings_next_label
import sbs3dViewer.resources.image_settings_previous_label
import sbs3dViewer.resources.image_settings_settings_label
import sbs3dViewer.resources.image_settings_share_label
import sbs3dViewer.resources.image_settings_shrink_controls_toggle_label
import sbs3dViewer.resources.ok_button
import sbs3dViewer.resources.share_destination_downloads
import sbs3dViewer.resources.share_destination_email
import sbs3dViewer.resources.share_dialog_title
import sbs3dViewer.resources.share_option_anaglyph
import sbs3dViewer.resources.share_option_left
import sbs3dViewer.resources.share_option_right
import sbs3dViewer.resources.share_option_sbs
import sbs3dViewer.resources.share_section_action
import sbs3dViewer.resources.share_section_image_type
import sbs3dViewer.resources.unsaved_align_changes_dialog_message
import sbs3dViewer.resources.unsaved_align_changes_dialog_title
import java.io.File

// Unlike Exif3dInfoPanel's InfoPanelShiftPercent, this label is meant to read as pinned to the
// screen glass rather than floating, so it's duplicated per half (for the same reason every other
// overlay is - a single copy would only appear in one eye) but not shifted apart.
private const val RawEditedLabelShiftPercent = 0f

// Same reasoning as RawEditedLabelShiftPercent: this is UI chrome, not a photo annotation, so it
// reads as pinned to the screen glass rather than floating.
private const val SettingsMenuShiftPercent = 0f

// Same sign convention as Exif3dInfoPanel's InfoPanelShiftPercent - the crop rectangle/blackout
// mask drawn while dragging is an overlay on top of the photo, so it gets the same per-half
// duplication + depth shift as everything else, per the user's spec ("draw a rectangle in 3D").
private const val CropOverlayShiftPercent = -0.01f

// Same rationale as CropOverlayShiftPercent, for the "Spot stereo issues" tool's pink rectangles.
private const val SpotIssueOverlayShiftPercent = -0.01f

// Hot pink - matches SpotStereoIssues.kt's PinkBgra (the color baked into the saved photo), so the
// live preview while drawing/reviewing looks the same as the result after Save.
private val SpotIssuePinkColor = Color(0xFFFF1493)

// Below this (screen px, in one eye-half's local space), a press-release is treated as an
// accidental click rather than a deliberate rectangle drag, so the tool stays in drawing mode
// instead of committing a near-zero-size rectangle. Shared by the crop tool and "Spot stereo
// issues" - both draw a rectangle by drag (see computeDragFraction).
private const val MinRectDragPx = 20f

// Case-insensitive-compared against File.extension (already lowercased at the call site) - see
// notLikely3DToken's doc for why .mpo is excluded.
private val JpegExtensions = setOf("jpg", "jpeg")

@Composable
fun ImageScreen(
    file: File,
    overrideBitmap: ImageBitmap? = null,
    showInfoPanel: Boolean = false,
    hasAlignedPreview: Boolean = false,
    isAligning: Boolean = false,
    alignToast: AlignToast? = null,
    saveToast: SaveToast? = null,
    shareToast: ShareToast? = null,
    keepBestOfEachOnly: Boolean = false,
    favoritesOnly: Boolean = false,
    excludeStereoIssues: Boolean = false,
    showPhotoFilters: Boolean = true,
    // False while playing a playlist - see InfoPanel's showEditTools.
    showEditTools: Boolean = true,
    checkLikelySideBySide: Boolean = true,
    halveLeftRightImages: Boolean = true,
    // Set only while playing a playlist photo with "force fullscreen by scrolling" on (see
    // FullscreenScrollStereoImage): the photo, its scrolling duration and the playlist's speed limit.
    fullscreenScrollPhoto: PlaylistItem? = null,
    fullscreenScrollDurationMs: Long? = null,
    scrollSpeedLimitPercent: Float = 0f,
    onFullscreenScrollDurationMs: (Long) -> Unit = {},
    shrinkControls: Boolean = false,
    initialMenuExpanded: Boolean = false,
    manualAlignMode: Boolean = false,
    manualAlignOffsetX: Float = 0f,
    manualAlignOffsetY: Float = 0f,
    manualAlignStepPercent: Float = 0.5f,
    cropMode: Boolean = false,
    cropRect: CropRectFraction? = null,
    spotIssuesMode: Boolean = false,
    spotIssueRects: List<IssueRectFraction> = emptyList(),
    clickAlignMode: Boolean = false,
    clickAlignLeftPoint: PointFraction? = null,
    clickAlignRightPoint: PointFraction? = null,
    clickAlignPreviewActive: Boolean = false,
    clickAlignPreview: ClickAlign.Preview? = null,
    pendingNavigation: PendingNavigationDirection? = null,
    onConfirmSaveAlignedAndNavigate: () -> Unit = {},
    onDiscardAlignedPreviewAndNavigate: () -> Unit = {},
    onCancelPendingNavigation: () -> Unit = {},
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
    onCancelAligned: () -> Unit = {},
    onStartManualAlign: () -> Unit = {},
    onCancelManualAlign: () -> Unit = {},
    onSaveManualAlign: () -> Unit = {},
    onStartCrop: () -> Unit = {},
    onCropRectFinalized: (CropRectFraction) -> Unit = {},
    onCancelCrop: () -> Unit = {},
    onSaveCrop: () -> Unit = {},
    onStartSpotIssues: () -> Unit = {},
    onSpotIssueRectAdded: (IssueRectFraction) -> Unit = {},
    onCancelSpotIssues: () -> Unit = {},
    onSaveSpotIssues: () -> Unit = {},
    onStartClickAlign: () -> Unit = {},
    onClickAlignPointSet: (isLeft: Boolean, PointFraction) -> Unit = { _, _ -> },
    onCancelClickAlign: () -> Unit = {},
    onSaveClickAlign: () -> Unit = {},
    onRequestClickAlignPreview: () -> Unit = {},
    onDeleteCurrentImage: () -> Unit = {},
    onDeleteKeepingLeft: () -> Unit = {},
    onDeleteKeepingRight: () -> Unit = {},
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit = {},
    onFavoritesOnlyChosen: (Boolean) -> Unit = {},
    onExcludeStereoIssuesChosen: (Boolean) -> Unit = {},
    onHalveLeftRightImagesChosen: (Boolean) -> Unit = {},
    onShrinkControlsChosen: (Boolean) -> Unit = {},
    onManualAlignStepPercentChosen: (Float) -> Unit = {},
    onExitFullscreen: () -> Unit = {},
    onNextImage: () -> Unit = {},
    onPreviousImage: () -> Unit = {},
    onToggleInfoPanel: () -> Unit = {},
    lastShareType: Share.ShareType = Share.ShareType.SBS,
    lastShareDestination: Share.Destination = Share.Destination.EMAIL,
    onShareChosen: (Share.ShareType, Share.Destination) -> Unit = { _, _ -> },
    onImageLoaded: () -> Unit = {},
    onRequestKeyboardFocus: () -> Unit = {},
    onDialogShownChanged: (Boolean) -> Unit = {},
) {
    // Decoded off the UI thread (large side-by-side 3D JPEGs can take a while) through
    // ImageDecodeCache - which also prefetches Next/Previous neighbors (see Main.kt) so most
    // navigation lands here with the bitmap already decoded - so a loading overlay drawn by the
    // caller (see Main.kt's isEnteringFullscreen/FullscreenLoadingOverlay) actually gets a chance
    // to render on a cold decode instead of the whole composition blocking until it finishes.
    var fileBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    // True once decode finishes and failed (corrupt/truncated/not-actually-an-image file) - shown
    // as an error placeholder instead of crashing the whole app, which double-clicking an
    // arbitrary file via Windows' file association could otherwise trigger. See
    // ImageDecodeCache.Result.Failed.
    var decodeFailed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(file) {
        decodeFailed = false
        when (val result = ImageDecodeCache.load(file)) {
            is ImageDecodeCache.Result.Loaded -> fileBitmap = result.bitmap
            ImageDecodeCache.Result.Failed -> decodeFailed = true
        }
    }
    val imageBitmap = overrideBitmap ?: fileBitmap
    LaunchedEffect(fileBitmap, decodeFailed) {
        if (fileBitmap != null || decodeFailed) onImageLoaded()
    }

    // Bumped (never reset) whenever a freshly-loaded JPEG's color profile across its vertical
    // midline reads as visually continuous rather than a stereo seam (see SideBySideLikeliness,
    // synced from the Camera 3D Android app's ShareFragment import-check) - i.e. the file probably
    // isn't a side-by-side 3D photo. A token rather than a Boolean so NotLikely3DToast re-flashes
    // even when navigating back to a photo already flagged once (see StereoToast's trigger-identity
    // doc). Limited to .jpg/.jpeg (not .mpo, whose composed-in-memory bitmap this check was never
    // measured against - see the LaunchedEffect(file) above). Skipped entirely when
    // [checkLikelySideBySide] is false (playing a playlist, whose photos may legitimately be
    // half-width or otherwise not match the heuristic).
    var notLikely3DToken by remember { mutableStateOf(0) }
    LaunchedEffect(fileBitmap) {
        val bitmap = fileBitmap
        if (checkLikelySideBySide && bitmap != null && file.extension.lowercase() in JpegExtensions) {
            val notLikely3D = withContext(Dispatchers.Default) {
                // SideBySideLikeliness only ever samples the two columns straddling the vertical
                // midline (x = width/2-1 and width/2, every row) - reading just that 2px-wide
                // strip via toPixelMap's startX/width instead of the whole bitmap avoids copying
                // an entire full-resolution stereo photo (tens of MB) into an IntArray just to
                // look at two columns of it. Matches isLikelySideBySide's own width<2 guard so a
                // 1px-wide (or narrower) bitmap never reaches the negative startX below.
                if (bitmap.width < 2) {
                    false
                } else {
                    val midX = bitmap.width / 2 - 1
                    val pixelMap = bitmap.toPixelMap(startX = midX, startY = 0, width = 2, height = bitmap.height)
                    !SideBySideLikeliness.isLikelySideBySide(bitmap.width, bitmap.height) { x, y -> pixelMap[x - midX, y].toArgb() }
                }
            }
            if (notLikely3D) notLikely3DToken++
        }
    }

    // Hosted here rather than inside Exif3dInfoPanel so the toast survives the panel being
    // toggled closed (pressing Shift/Ctrl again), which removes Exif3dInfoPanel (and any state
    // it holds) from composition.
    var exifUpdateToken by remember(file) { mutableStateOf(0) }

    // Opened by the settings menu's "Share" row (see SettingsMenuHalf) - which ShareType the
    // dialog offers is fixed, so this is just a visibility flag, closed as soon as a choice (or
    // Cancel) is made.
    var showShareDialog by remember(file) { mutableStateOf(false) }

    // Opened by the settings menu's "Settings" row (see SettingsMenuHalf) - a small dialog holding
    // every app-wide setting that isn't a plain on/off toggle simple enough for its own menu row
    // (today just manualAlignStepPercent). Not remember(file)-scoped like showShareDialog: it's an
    // app-wide panel unrelated to the current photo, so it should stay open across Next/Previous.
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Reported by InfoPanel (see its onDialogOpenChanged) so this can be folded into
    // anyDialogShown below. showInfoPanel-gated too, in case InfoPanel's own reset (its
    // DisposableEffect) races with a later recomposition.
    var infoPanelDialogOpen by remember(file) { mutableStateOf(false) }

    // Under "shrink controls" every dialog below renders in-scene (see ShrinkControls.kt's
    // Stereo3DAlertDialog) rather than as a separate native window, so its TextButtons/TextFields
    // are real, focusable Compose nodes in the SAME window as Main.kt's root Box - reported up via
    // onDialogShownChanged so Main.kt's onPreviewKeyEvent stops hijacking Escape/Space/arrow
    // keys/"A" as app-wide shortcuts while one is open, letting them reach the dialog instead (see
    // Stereo3DAlertDialogHalf's own Escape handling and its TextField's auto-focus). Once the
    // dialog closes, that focused node is disposed and Compose is left with no current focus owner
    // at all, so onPreviewKeyEvent would otherwise silently stop firing even though the root Box
    // modifier chain hasn't changed - re-requesting focus once every dialog here has closed
    // restores that path.
    val anyDialogShown = infoPanelDialogOpen || showShareDialog || showSettingsDialog || pendingNavigation != null
    LaunchedEffect(anyDialogShown) {
        onDialogShownChanged(anyDialogShown)
        if (!anyDialogShown) onRequestKeyboardFocus()
    }

    // Live drag corners while the crop rectangle is being drawn (left-half-local screen px, same
    // space Stereo3DCursorHost's cursor uses) - purely ephemeral UI state, reset whenever cropMode
    // goes false (Cancel/Save/Escape) so a stray in-progress drag never survives past that.
    var cropDragStartPx by remember(file) { mutableStateOf<Offset?>(null) }
    var cropDragCurrentPx by remember(file) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(cropMode) {
        if (!cropMode) {
            cropDragStartPx = null
            cropDragCurrentPx = null
        }
    }

    // Live drag corners while a "Spot stereo issues" rectangle is being drawn - same treatment as
    // cropDragStartPx/cropDragCurrentPx, but reset per spotIssuesMode rather than per rectangle
    // (see AppViewModel.addSpotIssueRect): the tool stays active after each release so another
    // rectangle can be drawn immediately.
    var spotIssueDragStartPx by remember(file) { mutableStateOf<Offset?>(null) }
    var spotIssueDragCurrentPx by remember(file) { mutableStateOf<Offset?>(null) }
    LaunchedEffect(spotIssuesMode) {
        if (!spotIssuesMode) {
            spotIssueDragStartPx = null
            spotIssueDragCurrentPx = null
        }
    }

    // Recomputes the Shift-held preview (see ClickAlign.Preview) whenever it's actually needed:
    // Shift just got pressed, or either point moved while Shift is already held. Left stale (not
    // cleared) once Shift releases - onRequestClickAlignPreview only kicks off a fresh Dispatchers.IO
    // computation, and re-showing the same preview instantly next time Shift is pressed again (before
    // either point moves) is preferable to a blank frame while it's recomputed for no reason.
    LaunchedEffect(clickAlignPreviewActive, clickAlignLeftPoint, clickAlignRightPoint) {
        if (clickAlignPreviewActive && clickAlignLeftPoint != null && clickAlignRightPoint != null) {
            onRequestClickAlignPreview()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val halfWidthPx = with(density) { (maxWidth / 2).toPx() }
        val boxHeightPx = with(density) { maxHeight.toPx() }

        Stereo3DCursorHost(
            rectDragActive = (cropMode && cropRect == null) || spotIssuesMode,
            rawClickActive = clickAlignMode,
            onRawClick = { halfLocal, isRightHalf ->
                val bitmap = imageBitmap
                if (bitmap != null) {
                    val halfWidthSrc = bitmap.width / 2
                    if (halveLeftRightImages) {
                        // Comparison layout (see ClickAlignComparisonLayout): both physical halves
                        // show an identical [left image | right image] pair, so isRightHalf is
                        // irrelevant - halfLocal is always relative to one physical half, which
                        // itself splits into two equal sub-boxes.
                        val subBoxWidthPx = halfWidthPx / 2f
                        val isLeftSub = halfLocal.x < subBoxWidthPx
                        val localX = if (isLeftSub) halfLocal.x else halfLocal.x - subBoxWidthPx
                        val point = pixelToImageFraction(Offset(localX, halfLocal.y), subBoxWidthPx, boxHeightPx, halfWidthSrc, bitmap.height, halveLeftRightImages)
                        onClickAlignPointSet(isLeftSub, point)
                    } else {
                        // Default layout: each physical half already shows one whole eye's photo
                        // (see StereoImage's doc), so isRightHalf alone says which eye was clicked.
                        val point = pixelToImageFraction(halfLocal, halfWidthPx, boxHeightPx, halfWidthSrc, bitmap.height, false)
                        onClickAlignPointSet(!isRightHalf, point)
                    }
                }
            },
            shrinkControls = shrinkControls,
            // Normally the cursor's depth registry is only consulted under "shrink controls" (see
            // Stereo3DCursorHost's doc) - overridden on here too while the click-align tool is
            // active, so the cursor matches the 0%-depth image/crosshairs it's hovering (see the
            // .cursor3DDepthTarget(0f) below) instead of always floating at the default -1%.
            useDepthRegistry = shrinkControls || clickAlignMode,
            // Aliases the mouse wheel to the Next/Previous arrow keys (see Main.kt's
            // onPreviewKeyEvent), same "backward" = forward-in-time convention as scrolling down a
            // list: scrolling backward/down (positive delta) advances to the next image, scrolling
            // forward/up (negative delta) goes back. Disabled while a photo tool is active or a
            // dialog is open, matching Main.kt's arrow-key handling, which repurposes/swallows
            // navigation during those modes.
            onScroll = { delta ->
                if (!manualAlignMode && !cropMode && !spotIssuesMode && !anyDialogShown) {
                    if (delta > 0) onNextImage() else if (delta < 0) onPreviousImage()
                }
            },
            onRectDragChange = { start, current ->
                if (spotIssuesMode) {
                    spotIssueDragStartPx = start
                    spotIssueDragCurrentPx = current
                } else {
                    cropDragStartPx = start
                    cropDragCurrentPx = current
                }
            },
            onRectDragEnd = { start, end ->
                val bitmap = imageBitmap
                if (bitmap != null) {
                    val frac = computeDragFraction(bitmap, halveLeftRightImages, halfWidthPx, boxHeightPx, start, end)
                    if (frac != null) {
                        if (spotIssuesMode) {
                            onSpotIssueRectAdded(IssueRectFraction(frac[0], frac[1], frac[2], frac[3]))
                        } else {
                            onCropRectFinalized(CropRectFraction(frac[0], frac[1], frac[2], frac[3]))
                        }
                    }
                }
                if (spotIssuesMode) {
                    spotIssueDragStartPx = null
                    spotIssueDragCurrentPx = null
                }
            },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    // Only while the click-align tool is active - see the useDepthRegistry doc
                    // above and CLAUDE.md's cursor3DDepthTarget note. 0% matches the depth the
                    // comparison layout/crosshairs/preview are all drawn at (no shift - see
                    // ClickAlignComparisonLayout's doc), unlike every other registered panel here,
                    // which floats at a small negative %.
                    .then(if (clickAlignMode) Modifier.cursor3DDepthTarget(0f) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                imageBitmap?.let { bitmap ->
                    val preview = clickAlignPreview
                    if (clickAlignMode && clickAlignPreviewActive && preview != null) {
                        when (preview) {
                            is ClickAlign.Preview.Split -> ClickAlignSplitPreview(preview.left, preview.right, halveLeftRightImages)
                            is ClickAlign.Preview.Anaglyph -> ClickAlignAnaglyphPreview(preview.bitmap)
                        }
                    } else if (clickAlignMode && halveLeftRightImages) {
                        ClickAlignComparisonLayout(bitmap, halveLeftRightImages)
                    } else if (fullscreenScrollPhoto != null && fullscreenScrollDurationMs != null && !manualAlignMode && !cropMode && cropRect == null) {
                        FullscreenScrollStereoImage(bitmap, fullscreenScrollPhoto, fullscreenScrollDurationMs, halveLeftRightImages,
                            scrollSpeedLimitPercent, onFullscreenScrollDurationMs)
                    } else {
                        StereoImage(bitmap, halveLeftRightImages, manualAlignOffsetX, manualAlignOffsetY, cropRect)
                    }
                    if (clickAlignMode && !(clickAlignPreviewActive && preview != null)) {
                        ClickAlignPointsOverlay(bitmap, halveLeftRightImages, shrinkControls, clickAlignLeftPoint, clickAlignRightPoint)
                    }
                }
                if (decodeFailed && imageBitmap == null) {
                    DecodeFailedMessage()
                }
                val dragStart = cropDragStartPx
                val dragCurrent = cropDragCurrentPx
                if (dragStart != null && dragCurrent != null) {
                    CropDrawOverlay(dragStart, dragCurrent)
                }
                imageBitmap?.let { bitmap ->
                    SpotIssueRectsOverlayIfAny(
                        bitmap, halveLeftRightImages, halfWidthPx, boxHeightPx,
                        spotIssueRects, spotIssueDragStartPx, spotIssueDragCurrentPx,
                    )
                }
                RawEditedLabelOverlay(file, shrinkControls)
                SettingsMenuOverlay(
                    keepBestOfEachOnly,
                    favoritesOnly,
                    excludeStereoIssues,
                    halveLeftRightImages,
                    shrinkControls,
                    onKeepBestOfEachOnlyChosen,
                    onFavoritesOnlyChosen,
                    onExcludeStereoIssuesChosen,
                    onHalveLeftRightImagesChosen,
                    onShrinkControlsChosen,
                    onExitFullscreen,
                    onNextImage,
                    onPreviousImage,
                    onToggleInfoPanel,
                    onOpenShare = { showShareDialog = true },
                    onOpenSettings = { showSettingsDialog = true },
                    initialExpanded = initialMenuExpanded,
                    showPhotoFilters = showPhotoFilters,
                )
                if (showInfoPanel) {
                    InfoPanel(
                        file,
                        shrinkControls = shrinkControls,
                        onDialogOpenChanged = { infoPanelDialogOpen = it },
                        onExifUpdated = { exifUpdateToken++ },
                        hasAlignedPreview = hasAlignedPreview,
                        isAligning = isAligning,
                        manualAlignMode = manualAlignMode,
                        hasManualOffset = manualAlignOffsetX != 0f || manualAlignOffsetY != 0f,
                        cropMode = cropMode,
                        hasCropRect = cropRect != null,
                        spotIssuesMode = spotIssuesMode,
                        hasSpotIssueRects = spotIssueRects.isNotEmpty(),
                        clickAlignMode = clickAlignMode,
                        hasClickAlignPoints = clickAlignLeftPoint != null && clickAlignRightPoint != null,
                        onAutoAlign = onAutoAlign,
                        onCorrectZoom = onCorrectZoom,
                        onSaveAligned = onSaveAligned,
                        onCancelAligned = onCancelAligned,
                        onStartManualAlign = onStartManualAlign,
                        onCancelManualAlign = onCancelManualAlign,
                        onSaveManualAlign = onSaveManualAlign,
                        onStartCrop = onStartCrop,
                        onCancelCrop = onCancelCrop,
                        onSaveCrop = onSaveCrop,
                        onStartSpotIssues = onStartSpotIssues,
                        onCancelSpotIssues = onCancelSpotIssues,
                        onSaveSpotIssues = onSaveSpotIssues,
                        onStartClickAlign = onStartClickAlign,
                        onCancelClickAlign = onCancelClickAlign,
                        onSaveClickAlign = onSaveClickAlign,
                        onDeleteCurrentImage = onDeleteCurrentImage,
                        onDeleteKeepingLeft = onDeleteKeepingLeft,
                        onDeleteKeepingRight = onDeleteKeepingRight,
                        showEditTools = showEditTools,
                    )
                }
                ExifUpdatedToast(exifUpdateToken)
                AlignResultToast(alignToast)
                SaveResultToast(saveToast)
                ShareResultToast(shareToast)
                NotLikely3DToast(notLikely3DToken)
                ClickAlignPreviewHintToast(visible = clickAlignMode && clickAlignPreviewActive && clickAlignPreview == null)
                // Rendered here rather than after Stereo3DCursorHost's closing brace (as an
                // ImageScreen-level sibling) so they stay descendants of its pointerInput Box and
                // LocalCursorHitRegistry CompositionLocalProvider - same reasoning as InfoPanel's
                // own dialogs (its showDeleteConfirmDialog/showWarningEraseDialog/... below), which
                // is why the cursor dot stays visible and cursor3DClickTarget works over THOSE but
                // not over a dialog declared outside this Box under "shrink controls" (a sibling
                // dialog escapes both).
                if (pendingNavigation != null) {
                    UnsavedAlignedChangesDialog(
                        shrinkControls = shrinkControls,
                        onSave = onConfirmSaveAlignedAndNavigate,
                        onDiscard = onDiscardAlignedPreviewAndNavigate,
                        onCancel = onCancelPendingNavigation,
                    )
                }
                if (showShareDialog) {
                    ShareTypeDialog(
                        shrinkControls = shrinkControls,
                        initialType = lastShareType,
                        initialDestination = lastShareDestination,
                        onConfirm = { type, destination ->
                            showShareDialog = false
                            onShareChosen(type, destination)
                        },
                        onDismiss = { showShareDialog = false },
                    )
                }
                if (showSettingsDialog) {
                    SettingsDialog(
                        shrinkControls = shrinkControls,
                        manualAlignStepPercent = manualAlignStepPercent,
                        onManualAlignStepPercentChosen = onManualAlignStepPercentChosen,
                        onDismiss = { showSettingsDialog = false },
                    )
                }
            }
        }
    }
}

/**
 * Shown in place of the photo when ImageDecodeCache.load couldn't decode [file] at all (a
 * corrupt/truncated JPEG, or a file that isn't actually an image despite its extension) - see the
 * decodeFailed state above. A single centered message rather than the per-half-duplicated,
 * depth-shifted treatment every other overlay in this app gets (see CLAUDE.md's stereo notes):
 * there's no photo underneath to fuse in 3D here, so it reads more like WelcomeScreen's or
 * FullscreenLoadingOverlay's plain single-copy text than like an annotation on top of a photo.
 */
@Composable
private fun DecodeFailedMessage() {
    Text(
        text = stringResource(Res.string.image_decode_failed_message),
        color = Color.White,
        fontSize = 20.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}

/**
 * Lets the user pick which single-image derivative of the current stereo photo to produce, and
 * whether it's attached to an outgoing email or copied to the Downloads folder (see Share.kt) -
 * goes through AdaptiveAlertDialog like every other confirmation dialog in this app (e.g.
 * InfoPanel's delete-choice dialog), so it's a plain, non-stereo-duplicated AlertDialog normally,
 * or an in-scene duplicated/shrunk one under "shrink controls" (see ShrinkControls.kt). Unlike a
 * plain confirmation dialog this needs two independent choices before it can act, so - unlike the
 * old one-tap-per-TextButton version - both are radio-button groups and [onConfirm] only fires
 * once on the OK button. [initialType]/[initialDestination] seed the radio selection with
 * AppViewModel's lastShareType/lastShareDestination (the previous OK'd choice, persisted across
 * restarts - see Share*Preference in AppViewModel.kt) rather than a fixed default, so re-opening
 * this dialog doesn't force re-picking both options every time.
 */
@Composable
private fun ShareTypeDialog(
    shrinkControls: Boolean,
    initialType: Share.ShareType,
    initialDestination: Share.Destination,
    onConfirm: (Share.ShareType, Share.Destination) -> Unit,
    onDismiss: () -> Unit,
) {
    var type by remember { mutableStateOf(initialType) }
    var destination by remember { mutableStateOf(initialDestination) }
    AdaptiveAlertDialog(
        shrinkControls = shrinkControls,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.share_dialog_title)) },
        text = {
            Column {
                Text(stringResource(Res.string.share_section_image_type), fontWeight = FontWeight.Bold)
                SettingsRadioRow(stringResource(Res.string.share_option_left), type == Share.ShareType.LEFT) { type = Share.ShareType.LEFT }
                SettingsRadioRow(stringResource(Res.string.share_option_right), type == Share.ShareType.RIGHT) { type = Share.ShareType.RIGHT }
                SettingsRadioRow(stringResource(Res.string.share_option_sbs), type == Share.ShareType.SBS) { type = Share.ShareType.SBS }
                SettingsRadioRow(stringResource(Res.string.share_option_anaglyph), type == Share.ShareType.ANAGLYPH) { type = Share.ShareType.ANAGLYPH }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(Res.string.share_section_action), fontWeight = FontWeight.Bold)
                SettingsRadioRow(stringResource(Res.string.share_destination_email), destination == Share.Destination.EMAIL) { destination = Share.Destination.EMAIL }
                SettingsRadioRow(stringResource(Res.string.share_destination_downloads), destination == Share.Destination.DOWNLOADS_FOLDER) { destination = Share.Destination.DOWNLOADS_FOLDER }
            }
        },
        confirmButton = {
            val onOk = { onConfirm(type, destination) }
            TextButton(onClick = onOk, modifier = Modifier.cursor3DClickTarget(onOk)) { Text(stringResource(Res.string.ok_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.cursor3DClickTarget(onDismiss)) { Text(stringResource(Res.string.cancel_button)) } },
    )
}

/**
 * Opened from the settings menu's "Settings" row (see SettingsMenuHalf) - holds every app-wide
 * setting that isn't a plain on/off toggle simple enough for its own menu row (today just
 * manualAlignStepPercent; future settings belong here too). Same AdaptiveAlertDialog + staged-
 * text-field-applied-on-confirm treatment as [InfoPanel]'s LegendTextDialog/StereoIssueCommentDialog
 * (edits live in [fieldValue] until "OK", not applied keystroke-by-keystroke - avoids the parsed/
 * reformatted value fighting the user mid-typing).
 */
@Composable
private fun SettingsDialog(shrinkControls: Boolean, manualAlignStepPercent: Float, onManualAlignStepPercentChosen: (Float) -> Unit, onDismiss: () -> Unit) {
    var fieldValue by remember { mutableStateOf(TextFieldValue("%.1f".format(manualAlignStepPercent))) }
    // See StereoIssueCommentDialog's matching comment.
    var caretVisible by remember { mutableStateOf(true) }
    LaunchedEffect(shrinkControls) {
        if (!shrinkControls) return@LaunchedEffect
        while (true) {
            delay(500)
            caretVisible = !caretVisible
        }
    }
    val onConfirm = {
        fieldValue.text.replace(',', '.').toFloatOrNull()?.let { trackMenuItem("manual_align_step_confirm"); onManualAlignStepPercentChosen(it) }
        onDismiss()
    }
    AdaptiveAlertDialog(
        shrinkControls = shrinkControls,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.image_settings_settings_label)) },
        text = {
            Column {
                ManualAlignStepSettingRow(fieldValue, shrinkControls, caretVisible, onValueChange = { fieldValue = it })
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.cursor3DClickTarget(onConfirm)) { Text(stringResource(Res.string.ok_button)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.cursor3DClickTarget(onDismiss)) { Text(stringResource(Res.string.cancel_button)) } },
    )
}

/** One row of [SettingsDialog]: AppViewModel.manualAlignStepPercent, edited as free text rather
 *  than nudged by fixed increments - same dual plain-TextField/[StereoBlinkingCaretTextField]
 *  treatment (and same auto-focus-without-a-click rationale) as StereoIssueCommentDialog's text
 *  field. */
@Composable
private fun ManualAlignStepSettingRow(fieldValue: TextFieldValue, shrinkControls: Boolean, caretVisible: Boolean, onValueChange: (TextFieldValue) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.image_settings_manual_align_step_label))
        Spacer(Modifier.width(12.dp))
        if (shrinkControls) {
            StereoBlinkingCaretTextField(
                value = fieldValue,
                onValueChange = onValueChange,
                caretVisible = caretVisible,
                modifier = Modifier.width(64.dp).focusRequester(focusRequester),
                textStyle = LocalTextStyle.current.copy(color = Color.Black, fontSize = SettingsMenuShrunkFontSize),
            )
        } else {
            CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = Color.Black)) {
                TextField(
                    value = fieldValue,
                    onValueChange = onValueChange,
                    modifier = Modifier.width(90.dp).focusRequester(focusRequester),
                    colors = TextFieldDefaults.colors(focusedTextColor = Color.Black, unfocusedTextColor = Color.Black),
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        Text("%", style = TextStyle(fontSize = if (shrinkControls) SettingsMenuShrunkFontSize else TextUnit.Unspecified))
    }
}

/**
 * Asks before Next/Previous (see AppViewModel.pendingNavigation) discards an unsaved auto-align/
 * correct-zoom preview - the preview only lives in memory (PhotoToolsState.alignedPreview) until
 * Save is pressed, so navigating away without asking would silently lose it. Same
 * AdaptiveAlertDialog treatment as [ShareTypeDialog].
 */
@Composable
private fun UnsavedAlignedChangesDialog(shrinkControls: Boolean, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    AdaptiveAlertDialog(
        shrinkControls = shrinkControls,
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.unsaved_align_changes_dialog_title)) },
        text = { Text(stringResource(Res.string.unsaved_align_changes_dialog_message)) },
        confirmButton = { TextButton(onClick = onSave, modifier = Modifier.cursor3DClickTarget(onSave)) { Text(stringResource(Res.string.align_save_button)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard, modifier = Modifier.cursor3DClickTarget(onDiscard)) { Text(stringResource(Res.string.discard_button)) }
                TextButton(onClick = onCancel, modifier = Modifier.cursor3DClickTarget(onCancel)) { Text(stringResource(Res.string.cancel_button)) }
            }
        },
    )
}

/**
 * Live feedback while dragging out a crop rectangle (see Exif3dInfoPanel's Crop button and
 * ImageScreen's onCropDragEnd): everything outside [dragStartPx]/[dragCurrentPx]'s rectangle is
 * blacked out and the rectangle itself gets a white outline, duplicated per half and offset by
 * [CropOverlayShiftPercent] like every other overlay - both corners are already in left-half-local
 * screen px (see Stereo3DCursorHost), so both halves draw the identical rectangle, just shifted
 * apart for the 3D read.
 */
@Composable
private fun CropDrawOverlay(dragStartPx: Offset, dragCurrentPx: Offset) {
    val rectPx = Rect(
        left = minOf(dragStartPx.x, dragCurrentPx.x),
        top = minOf(dragStartPx.y, dragCurrentPx.y),
        right = maxOf(dragStartPx.x, dragCurrentPx.x),
        bottom = maxOf(dragStartPx.y, dragCurrentPx.y),
    )
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * CropOverlayShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { CropDrawOverlayHalf(rectPx, offsetX = -shift / 2) }
            Box(Modifier.fillMaxSize().weight(1f)) { CropDrawOverlayHalf(rectPx, offsetX = shift / 2) }
        }
    }
}

@Composable
private fun CropDrawOverlayHalf(rectPx: Rect, offsetX: Dp) {
    Canvas(Modifier.fillMaxSize().offset(x = offsetX)) {
        val maskColor = Color.Black.copy(alpha = 0.7f)
        if (rectPx.top > 0f) {
            drawRect(maskColor, topLeft = Offset(0f, 0f), size = Size(size.width, rectPx.top))
        }
        if (rectPx.bottom < size.height) {
            drawRect(maskColor, topLeft = Offset(0f, rectPx.bottom), size = Size(size.width, size.height - rectPx.bottom))
        }
        if (rectPx.left > 0f) {
            drawRect(maskColor, topLeft = Offset(0f, rectPx.top), size = Size(rectPx.left, rectPx.height))
        }
        if (rectPx.right < size.width) {
            drawRect(maskColor, topLeft = Offset(rectPx.right, rectPx.top), size = Size(size.width - rectPx.right, rectPx.height))
        }
        drawRect(Color.White, topLeft = rectPx.topLeft, size = rectPx.size, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Converts a raw drag (left-half-local screen px, same space Stereo3DCursorHost's cursor uses)
 * into a fraction (0..1) of one eye-half's width/height - shared by the crop tool's onCropRectFinalized
 * and "Spot stereo issues"' onSpotIssueRectAdded (see Stereo3DCursorHost's onRectDragEnd), since
 * both draw a rectangle by drag against the same full, uncropped eye-half (neither tool is
 * start-able while the other, or manual-align, is active - see AppViewModel.startCrop/startSpotIssues).
 * Returns null if the drag was too small ([MinRectDragPx], an accidental click rather than a
 * deliberate rectangle) or the image hasn't been measured yet.
 */
private fun computeDragFraction(
    bitmap: ImageBitmap,
    halveLeftRightImages: Boolean,
    halfWidthPx: Float,
    boxHeightPx: Float,
    start: Offset,
    end: Offset,
): FloatArray? {
    val left = minOf(start.x, end.x)
    val top = minOf(start.y, end.y)
    val right = maxOf(start.x, end.x)
    val bottom = maxOf(start.y, end.y)
    if (right - left < MinRectDragPx || bottom - top < MinRectDragPx) return null
    val halfWidthSrc = bitmap.width / 2
    val effectiveWidthPx = if (halveLeftRightImages) halfWidthSrc / 2f else halfWidthSrc.toFloat()
    val scale = minOf(halfWidthPx / effectiveWidthPx, boxHeightPx / bitmap.height)
    val dstWidth = effectiveWidthPx * scale
    val dstHeight = bitmap.height * scale
    if (dstWidth <= 0f || dstHeight <= 0f) return null
    val dstOffsetX = (halfWidthPx - dstWidth) / 2f
    val dstOffsetY = (boxHeightPx - dstHeight) / 2f
    val fx = ((left - dstOffsetX) / dstWidth).coerceIn(0f, 1f)
    val fy = ((top - dstOffsetY) / dstHeight).coerceIn(0f, 1f)
    val fRight = ((right - dstOffsetX) / dstWidth).coerceIn(0f, 1f)
    val fBottom = ((bottom - dstOffsetY) / dstHeight).coerceIn(0f, 1f)
    val fw = (fRight - fx).coerceAtLeast(0.01f)
    val fh = (fBottom - fy).coerceAtLeast(0.01f)
    return floatArrayOf(fx, fy, fw, fh)
}

/** Inverse of [computeDragFraction]'s scale/offset math: converts one already-drawn [IssueRectFraction]
 *  back into left-half-local screen px, so it can be redrawn as an overlay alongside the live drag. */
private fun issueRectToPx(rect: IssueRectFraction, bitmap: ImageBitmap, halveLeftRightImages: Boolean, halfWidthPx: Float, boxHeightPx: Float): Rect {
    val halfWidthSrc = bitmap.width / 2
    val effectiveWidthPx = if (halveLeftRightImages) halfWidthSrc / 2f else halfWidthSrc.toFloat()
    val scale = minOf(halfWidthPx / effectiveWidthPx, boxHeightPx / bitmap.height)
    val dstWidth = effectiveWidthPx * scale
    val dstHeight = bitmap.height * scale
    val dstOffsetX = (halfWidthPx - dstWidth) / 2f
    val dstOffsetY = (boxHeightPx - dstHeight) / 2f
    val left = dstOffsetX + rect.x * dstWidth
    val top = dstOffsetY + rect.y * dstHeight
    return Rect(left, top, left + rect.width * dstWidth, top + rect.height * dstHeight)
}

/**
 * Single-point counterpart to [computeDragFraction]/[issueRectToPx]'s scale/offset math, generalized
 * to an arbitrary box/source size (not just "one eye-half's own box") so ClickAlign's two layouts -
 * the default full [halfWidthPx]-wide box and the comparison layout's quarter-window sub-boxes (see
 * [ClickAlignComparisonLayout]) - can both convert a single click/point through the same math a
 * dragged rectangle already uses. [halveLeftRightImages] applies the same squeeze [StereoHalfImage]
 * does, so a point placed in the comparison layout survives round-tripping if the display mode
 * toggles - though in practice this tool is only shown in one mode at a time (see PhotoToolsState).
 */
private fun pixelToImageFraction(
    pos: Offset,
    boxWidthPx: Float,
    boxHeightPx: Float,
    srcWidth: Int,
    srcHeight: Int,
    halveLeftRightImages: Boolean,
): PointFraction {
    val effectiveWidthPx = if (halveLeftRightImages) srcWidth / 2f else srcWidth.toFloat()
    val scale = minOf(boxWidthPx / effectiveWidthPx, boxHeightPx / srcHeight)
    val dstWidth = effectiveWidthPx * scale
    val dstHeight = srcHeight * scale
    if (dstWidth <= 0f || dstHeight <= 0f) return PointFraction(0.5f, 0.5f)
    val dstOffsetX = (boxWidthPx - dstWidth) / 2f
    val dstOffsetY = (boxHeightPx - dstHeight) / 2f
    val fx = ((pos.x - dstOffsetX) / dstWidth).coerceIn(0f, 1f)
    val fy = ((pos.y - dstOffsetY) / dstHeight).coerceIn(0f, 1f)
    return PointFraction(fx, fy)
}

/** Inverse of [pixelToImageFraction] - converts an already-recorded [PointFraction] back into a
 *  box-local screen position, so [ClickAlignPointsOverlay] can draw a crosshair at it. */
private fun imageFractionToPixel(
    point: PointFraction,
    boxWidthPx: Float,
    boxHeightPx: Float,
    srcWidth: Int,
    srcHeight: Int,
    halveLeftRightImages: Boolean,
): Offset {
    val effectiveWidthPx = if (halveLeftRightImages) srcWidth / 2f else srcWidth.toFloat()
    val scale = minOf(boxWidthPx / effectiveWidthPx, boxHeightPx / srcHeight)
    val dstWidth = effectiveWidthPx * scale
    val dstHeight = srcHeight * scale
    val dstOffsetX = (boxWidthPx - dstWidth) / 2f
    val dstOffsetY = (boxHeightPx - dstHeight) / 2f
    return Offset(dstOffsetX + point.x * dstWidth, dstOffsetY + point.y * dstHeight)
}

/**
 * Renders every already-drawn "Spot stereo issues" rectangle plus the one currently being dragged
 * (if any), all in pink - see [SpotIssuePinkColor] (matches the color SpotStereoIssues.kt bakes
 * into the saved photo) and [SpotIssueOverlayShiftPercent]. No-op while nothing to show, same
 * "don't compose an empty overlay" treatment as RawEditedLabelOverlay.
 */
@Composable
private fun SpotIssueRectsOverlayIfAny(
    bitmap: ImageBitmap,
    halveLeftRightImages: Boolean,
    halfWidthPx: Float,
    boxHeightPx: Float,
    rects: List<IssueRectFraction>,
    liveDragStartPx: Offset?,
    liveDragCurrentPx: Offset?,
) {
    val screenRects = rects.map { issueRectToPx(it, bitmap, halveLeftRightImages, halfWidthPx, boxHeightPx) }
    val liveRect = if (liveDragStartPx != null && liveDragCurrentPx != null) {
        Rect(
            left = minOf(liveDragStartPx.x, liveDragCurrentPx.x),
            top = minOf(liveDragStartPx.y, liveDragCurrentPx.y),
            right = maxOf(liveDragStartPx.x, liveDragCurrentPx.x),
            bottom = maxOf(liveDragStartPx.y, liveDragCurrentPx.y),
        )
    } else null
    val allRects = if (liveRect != null) screenRects + liveRect else screenRects
    if (allRects.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * SpotIssueOverlayShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { SpotIssueRectsOverlayHalf(allRects, offsetX = -shift / 2) }
            Box(Modifier.fillMaxSize().weight(1f)) { SpotIssueRectsOverlayHalf(allRects, offsetX = shift / 2) }
        }
    }
}

@Composable
private fun SpotIssueRectsOverlayHalf(rectsPx: List<Rect>, offsetX: Dp) {
    Canvas(Modifier.fillMaxSize().offset(x = offsetX)) {
        rectsPx.forEach { rectPx ->
            drawRect(SpotIssuePinkColor, topLeft = rectPx.topLeft, size = rectPx.size, style = Stroke(width = 3.dp.toPx()))
        }
    }
}

/**
 * Crops the combined L+R photo apart and draws each eye-half fit and centered within its own half
 * of the window, full-bleed - the source file already has each eye at full native resolution side
 * by side (see CLAUDE.md's "full-width SBS" note). Always splitting this way (rather than fitting
 * the combined bitmap as one unit) keeps the L/R split centered on each half's own midpoint: for a
 * source image taller (relative to width) than the window, fitting as one unit would only letterbox
 * the two outer edges, pushing the split off-center from each half's own midpoint.
 *
 * When [halveLeftRightImages] is on, each half is additionally squeezed horizontally by 2 before
 * being fit - a Half-SBS 3D monitor (native window width, hardware unsqueezes each half per eye)
 * needs that squeeze; a Full-SBS monitor (native window width already double, no hardware unsqueeze)
 * wants the toggle off.
 *
 * [manualAlignOffsetX]/[manualAlignOffsetY] (fractions of the eye-half's width/height, 0 unless
 * manual-align mode is active - see PhotoToolsState.manualAlignOffsetX/Y) nudge the right half
 * only, but rather than leaving a blank gap where the shift no longer overlaps the left half, both
 * halves are cropped live to their common overlapping region - the same math
 * ManualAlign.saveManualAlign applies to the actual file at save time, so this preview is WYSIWYG.
 *
 * [cropRect], once the crop tool's rectangle has been released (see ImageScreen's onCropDragEnd),
 * further restricts both halves to that same relative fraction - same "only show what would be
 * saved" WYSIWYG treatment, and this is literally what makes the post-release screen "the preview
 * shows only the crop area": no separate cropped bitmap is materialized, this just draws a smaller
 * source rect. Mutually exclusive with [manualAlignOffsetX]/[manualAlignOffsetY] in practice (the
 * crop tool can't be started while manual-align is active - see AppViewModel.startCrop), so when
 * present it simply overrides the dx/dy-derived region instead of composing with it.
 */
@Composable
private fun StereoImage(
    bitmap: ImageBitmap,
    halveLeftRightImages: Boolean,
    manualAlignOffsetX: Float = 0f,
    manualAlignOffsetY: Float = 0f,
    cropRect: CropRectFraction? = null,
) {
    val halfWidth = bitmap.width / 2
    val heightPx = bitmap.height
    val rawDx = (manualAlignOffsetX * halfWidth).roundToInt()
    val rawDy = (manualAlignOffsetY * heightPx).roundToInt()
    val dx = rawDx.coerceIn(-(halfWidth - 1).coerceAtLeast(0), (halfWidth - 1).coerceAtLeast(0))
    val dy = rawDy.coerceIn(-(heightPx - 1).coerceAtLeast(0), (heightPx - 1).coerceAtLeast(0))
    val cropWidth = halfWidth - abs(dx)
    val cropHeight = heightPx - abs(dy)
    var leftOffset = IntOffset(maxOf(dx, 0), maxOf(dy, 0))
    var rightOffset = IntOffset(halfWidth + maxOf(-dx, 0), maxOf(-dy, 0))
    var cropSize = IntSize(cropWidth, cropHeight)
    if (cropRect != null) {
        val rx = (cropRect.x * halfWidth).toInt().coerceIn(0, halfWidth - 1)
        val ry = (cropRect.y * heightPx).toInt().coerceIn(0, heightPx - 1)
        val rw = (cropRect.width * halfWidth).toInt().coerceIn(1, halfWidth - rx)
        val rh = (cropRect.height * heightPx).toInt().coerceIn(1, heightPx - ry)
        leftOffset = IntOffset(rx, ry)
        rightOffset = IntOffset(halfWidth + rx, ry)
        cropSize = IntSize(rw, rh)
    }
    Row(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(bitmap, leftOffset, cropSize, halveLeftRightImages)
        }
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(bitmap, rightOffset, cropSize, halveLeftRightImages)
        }
    }
}

/**
 * Crops [bitmap] to the [srcOffset]/[srcSize] region and fits+centers it within this composable's
 * own box (squeezing its width by 2 first when [halveLeftRightImages] is on). Plain [Image] can't
 * crop a sub-region of a bitmap (its `contentScale` always maps the whole source), so this draws
 * directly via [Canvas]'s source-rect [drawImage].
 */
@Composable
private fun StereoHalfImage(bitmap: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, halveLeftRightImages: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val effectiveWidthPx = if (halveLeftRightImages) srcSize.width / 2f else srcSize.width.toFloat()
        val scale = minOf(size.width / effectiveWidthPx, size.height / srcSize.height)
        val dstWidth = effectiveWidthPx * scale
        val dstHeight = srcSize.height * scale
        drawImage(
            image = bitmap,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset(((size.width - dstWidth) / 2).toInt(), ((size.height - dstHeight) / 2).toInt()),
            dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

// Hot pink, matching SpotIssuePinkColor - the crosshairs marking each clicked point (see
// ClickAlignPointsOverlay) get the same "pink" treatment the user asked for.
private val ClickAlignPinkColor = SpotIssuePinkColor
private val ClickAlignCrosshairArm = 14.dp
private val ClickAlignCrosshairStroke = 3.dp

/**
 * The "click matching points" tool's layout for half-width display (see CLAUDE.md's Stereo/SBS
 * note and PhotoToolsState.clickAlignMode): both physical halves of the window show an *identical*
 * flat side-by-side pair - the left-eye photo, then the right-eye photo - rather than the normal
 * per-eye stereo split, so the user can see and click both images regardless of which half they're
 * looking at, without needing this to fuse as a stereo image (it isn't one). No depth shift, unlike
 * every other duplicated-per-half overlay in this app - the two copies are meant to read as
 * literally identical, not floating apart.
 */
@Composable
private fun ClickAlignComparisonLayout(bitmap: ImageBitmap, halveLeftRightImages: Boolean) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignComparisonHalf(bitmap, halveLeftRightImages) }
        Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignComparisonHalf(bitmap, halveLeftRightImages) }
    }
}

@Composable
private fun ClickAlignComparisonHalf(bitmap: ImageBitmap, halveLeftRightImages: Boolean) {
    val halfWidth = bitmap.width / 2
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(bitmap, IntOffset(0, 0), IntSize(halfWidth, bitmap.height), halveLeftRightImages)
        }
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(bitmap, IntOffset(halfWidth, 0), IntSize(halfWidth, bitmap.height), halveLeftRightImages)
        }
    }
}

/**
 * Shift-held preview in half-width display mode (see ClickAlign.Preview.Split's doc): the real
 * per-eye split the saved result would actually look like, in place of [ClickAlignComparisonLayout]'s
 * flat comparison view - same layout shape as [StereoImage] but from two already-cropped/aligned
 * bitmaps instead of one combined source with an offset.
 */
@Composable
private fun ClickAlignSplitPreview(left: ImageBitmap, right: ImageBitmap, halveLeftRightImages: Boolean) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(left, IntOffset(0, 0), IntSize(left.width, left.height), halveLeftRightImages)
        }
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            StereoHalfImage(right, IntOffset(0, 0), IntSize(right.width, right.height), halveLeftRightImages)
        }
    }
}

/**
 * Shift-held preview in full-width display mode (see ClickAlign.Preview.Anaglyph's doc): the two
 * aligned crops combined into one red/cyan image, full-bleed across the *whole* window - not split,
 * not duplicated per half, since an anaglyph is meant to be viewed as one unified image.
 */
@Composable
private fun ClickAlignAnaglyphPreview(bitmap: ImageBitmap) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val dstWidth = bitmap.width * scale
        val dstHeight = bitmap.height * scale
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(((size.width - dstWidth) / 2).toInt(), ((size.height - dstHeight) / 2).toInt()),
            dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * Draws a pink crosshair at [leftPoint]/[rightPoint] (see PhotoToolsState.clickAlignLeftPoint/
 * RightPoint), whichever are non-null, in the layout currently showing (see ImageScreen's
 * clickAlignMode branch) - the comparison layout's two-sub-box-per-half shape when
 * [halveLeftRightImages] is on, or the plain per-half shape otherwise. No-op while neither point is
 * set yet, same "don't compose an empty overlay" treatment as SpotIssueRectsOverlayIfAny.
 */
@Composable
private fun ClickAlignPointsOverlay(bitmap: ImageBitmap, halveLeftRightImages: Boolean, shrinkControls: Boolean, leftPoint: PointFraction?, rightPoint: PointFraction?) {
    if (leftPoint == null && rightPoint == null) return
    if (halveLeftRightImages) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignComparisonCrosshairs(bitmap, halveLeftRightImages, shrinkControls, leftPoint, rightPoint) }
            Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignComparisonCrosshairs(bitmap, halveLeftRightImages, shrinkControls, leftPoint, rightPoint) }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignCrosshairInBox(leftPoint, bitmap, halveLeftRightImages, shrinkControls) }
            Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignCrosshairInBox(rightPoint, bitmap, halveLeftRightImages, shrinkControls) }
        }
    }
}

@Composable
private fun ClickAlignComparisonCrosshairs(bitmap: ImageBitmap, halveLeftRightImages: Boolean, shrinkControls: Boolean, leftPoint: PointFraction?, rightPoint: PointFraction?) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignCrosshairInBox(leftPoint, bitmap, halveLeftRightImages, shrinkControls) }
        Box(Modifier.fillMaxSize().weight(1f)) { ClickAlignCrosshairInBox(rightPoint, bitmap, halveLeftRightImages, shrinkControls) }
    }
}

/**
 * Drawn as a small fixed-size box offset to [point]'s screen position - same idiom as Cursor3D.kt's
 * CursorDot - rather than drawing straight into a full-size Canvas, so [shrinkHorizontally] can
 * squeeze just the crosshair's own width by half around its own center (per the user's ask that it
 * "follow the same rules" as every other control under "shrink controls") without moving where it
 * points, the same way the cursor dot itself already does.
 */
@Composable
private fun ClickAlignCrosshairInBox(point: PointFraction?, bitmap: ImageBitmap, halveLeftRightImages: Boolean, shrinkControls: Boolean) {
    if (point == null) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val boxWidthPx = with(density) { maxWidth.toPx() }
        val boxHeightPx = with(density) { maxHeight.toPx() }
        val halfWidthSrc = bitmap.width / 2
        val pos = imageFractionToPixel(point, boxWidthPx, boxHeightPx, halfWidthSrc, bitmap.height, halveLeftRightImages)
        val xDp = with(density) { pos.x.toDp() }
        val yDp = with(density) { pos.y.toDp() }
        Box(
            Modifier
                .offset(x = xDp - ClickAlignCrosshairArm, y = yDp - ClickAlignCrosshairArm)
                .size(ClickAlignCrosshairArm * 2)
                .shrinkHorizontally(shrinkControls, TransformOrigin.Center),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val armPx = size.width / 2f
                val strokePx = ClickAlignCrosshairStroke.toPx()
                drawLine(ClickAlignPinkColor, Offset(0f, armPx), Offset(size.width, armPx), strokeWidth = strokePx)
                drawLine(ClickAlignPinkColor, Offset(armPx, 0f), Offset(armPx, size.height), strokeWidth = strokePx)
            }
        }
    }
}

/**
 * "raw"/"edited"/"editedN"/"stereo issues spotting" badge derived from [file]'s name (see
 * GalleryScreen.kt's rawEditedDisplayLabel, which this reuses so the fullscreen view and the
 * gallery thumbnails agree), shown at the top end of each half so it reads in 3D like every other
 * overlay - duplicated per half and offset by [RawEditedLabelShiftPercent] (0%, i.e. no depth shift
 * here) of half the screen width, same technique as Exif3dInfoPanel. Renders nothing for
 * arbitrarily-named JPEGs that don't carry the marker.
 */
@Composable
private fun RawEditedLabelOverlay(file: File, shrinkControls: Boolean) {
    val label = remember(file) { rawEditedDisplayLabel(file) }
    if (label.isEmpty()) return
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * RawEditedLabelShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) { RawEditedLabelHalf(label, offsetX = -shift / 2, shrinkControls) }
            Box(Modifier.fillMaxSize().weight(1f)) { RawEditedLabelHalf(label, offsetX = shift / 2, shrinkControls) }
        }
    }
}

@Composable
private fun RawEditedLabelHalf(label: String, offsetX: Dp, shrinkControls: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().padding(end = 24.dp, top = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .shrinkHorizontally(shrinkControls, TransformOrigin(1f, 0f))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    shadow = Shadow(color = Color.Black, blurRadius = 3f, offset = Offset(2f, 2f)),
                ),
            )
        }
    }
}

/**
 * Settings gear at the top start of each half (see [SettingsMenuShiftPercent] for why it's
 * pinned rather than floating, same technique as [RawEditedLabelOverlay]/Exif3dInfoPanel).
 * Tapping it opens a small panel with the "keep best of each" switch; open/closed state is
 * hoisted above the per-half Row so tapping either half's gear opens both.
 */
@Composable
private fun SettingsMenuOverlay(
    keepBestOfEachOnly: Boolean,
    favoritesOnly: Boolean,
    excludeStereoIssues: Boolean,
    halveLeftRightImages: Boolean,
    shrinkControls: Boolean,
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
    onFavoritesOnlyChosen: (Boolean) -> Unit,
    onExcludeStereoIssuesChosen: (Boolean) -> Unit,
    onHalveLeftRightImagesChosen: (Boolean) -> Unit,
    onShrinkControlsChosen: (Boolean) -> Unit,
    onExitFullscreen: () -> Unit,
    onNextImage: () -> Unit,
    onPreviousImage: () -> Unit,
    onToggleInfoPanel: () -> Unit,
    onOpenShare: () -> Unit,
    onOpenSettings: () -> Unit,
    initialExpanded: Boolean = false,
    showPhotoFilters: Boolean = true,
) {
    var expanded by remember { mutableStateOf(initialExpanded) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * SettingsMenuShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = -shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, favoritesOnly, excludeStereoIssues, halveLeftRightImages, shrinkControls, onKeepBestOfEachOnlyChosen, onFavoritesOnlyChosen, onExcludeStereoIssuesChosen, onHalveLeftRightImagesChosen, onShrinkControlsChosen, onExitFullscreen, onNextImage, onPreviousImage, onToggleInfoPanel, onOpenShare, onOpenSettings, showPhotoFilters = showPhotoFilters)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                SettingsMenuHalf(offsetX = shift / 2, expanded, { expanded = !expanded }, keepBestOfEachOnly, favoritesOnly, excludeStereoIssues, halveLeftRightImages, shrinkControls, onKeepBestOfEachOnlyChosen, onFavoritesOnlyChosen, onExcludeStereoIssuesChosen, onHalveLeftRightImagesChosen, onShrinkControlsChosen, onExitFullscreen, onNextImage, onPreviousImage, onToggleInfoPanel, onOpenShare, onOpenSettings, showPhotoFilters = showPhotoFilters)
            }
        }
    }
}

@Composable
private fun SettingsMenuHalf(
    offsetX: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    keepBestOfEachOnly: Boolean,
    favoritesOnly: Boolean,
    excludeStereoIssues: Boolean,
    halveLeftRightImages: Boolean,
    shrinkControls: Boolean,
    onKeepBestOfEachOnlyChosen: (Boolean) -> Unit,
    onFavoritesOnlyChosen: (Boolean) -> Unit,
    onExcludeStereoIssuesChosen: (Boolean) -> Unit,
    onHalveLeftRightImagesChosen: (Boolean) -> Unit,
    onShrinkControlsChosen: (Boolean) -> Unit,
    onExitFullscreen: () -> Unit,
    onNextImage: () -> Unit,
    onPreviousImage: () -> Unit,
    onToggleInfoPanel: () -> Unit,
    onOpenShare: () -> Unit,
    onOpenSettings: () -> Unit,
    showPhotoFilters: Boolean = true,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
            SettingsMenuGear(shrinkControls, SettingsMenuShiftPercent, onToggleExpanded)
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SettingsMenuPanel(shrinkControls, SettingsMenuShiftPercent) {
                    // Not offered while a playlist plays: its items are always all shown (see
                    // AppViewModel.anyPhotoFilterActive).
                    if (showPhotoFilters) {
                        // Grouped together (tight spacing, no dividing line needed) since all three
                        // narrow down which photos Next/Previous land on - kept visually distinct from
                        // the unrelated toggles/actions below via the wider gap after the group.
                        Column {
                            SettingsMenuToggleRow(stringResource(Res.string.image_settings_keep_best_of_each_toggle_label), keepBestOfEachOnly, shrinkControls) { trackMenuItem("keep_best_of_each"); onKeepBestOfEachOnlyChosen(it) }
                            Spacer(Modifier.height(8.dp))
                            SettingsMenuToggleRow(stringResource(Res.string.image_settings_favorites_only_toggle_label), favoritesOnly, shrinkControls) { trackMenuItem("favorites_only"); onFavoritesOnlyChosen(it) }
                            Spacer(Modifier.height(8.dp))
                            SettingsMenuToggleRow(stringResource(Res.string.image_settings_exclude_stereo_issues_toggle_label), excludeStereoIssues, shrinkControls) { trackMenuItem("exclude_stereo_issues"); onExcludeStereoIssuesChosen(it) }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    SettingsMenuToggleRow(stringResource(Res.string.image_settings_halve_left_right_toggle_label), halveLeftRightImages, shrinkControls) { trackMenuItem("halve_left_right"); onHalveLeftRightImagesChosen(it) }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuToggleRow(stringResource(Res.string.image_settings_shrink_controls_toggle_label), shrinkControls, shrinkControls) { trackMenuItem("shrink_controls"); onShrinkControlsChosen(it) }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_settings_label), shrinkControls) { trackMenuItem("settings"); onOpenSettings() }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_next_label), shrinkControls) { trackMenuItem("next"); onNextImage() }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_previous_label), shrinkControls) { trackMenuItem("previous"); onPreviousImage() }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_info_panel_label), shrinkControls) { trackMenuItem("info_panel"); onToggleInfoPanel() }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_share_label), shrinkControls) { trackMenuItem("share"); onOpenShare() }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_exit_fullscreen_label), shrinkControls) { trackMenuItem("exit_fullscreen"); onExitFullscreen() }
                }
            }
        }
    }
}

