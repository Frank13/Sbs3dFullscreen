import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

/**
 * The three mutually-exclusive per-photo edit tools (manual align, crop, "spot stereo issues")
 * plus the auto-align/correct-zoom preview - all state that's local to whichever photo is
 * currently on screen in ImageView, owned by AppViewModel as `photoTools`. Pulled out of
 * AppViewModel because every navigation method (onFilesChosen, showNextImage,
 * showPreviousImage, ...) had to remember to reset all four pieces of state separately;
 * [resetAll] is the one call each of them needs now, and the mutual-exclusion guard the three
 * start*() functions used to repeat individually is centralized in [anyToolActive].
 */
class PhotoToolsState {
    // Ephemeral auto-align/correct-zoom result for the current image only (not persisted to
    // disk) - see AppViewModel.applyAlignedPreview.
    var alignedPreview by mutableStateOf<ImageBitmap?>(null)
        private set

    // Which algorithm produced alignedPreview - needed so Save knows what to redo against the
    // original file (see AppViewModel.performSaveAligned).
    var pendingAlignKind by mutableStateOf<AlignKind?>(null)
        private set

    // True while the user is nudging the right eye-half with arrow keys (see Main.kt's
    // onPreviewKeyEvent and startManualAlign below) - drives AlignButtonsRow's Cancel/Save pair and
    // locks out the auto-align buttons/other keyboard shortcuts so the two pipelines can't mix.
    var manualAlignMode by mutableStateOf(false)
        private set

    // Accumulated offset applied to the right half only - a fraction of the eye-half's
    // width(X)/height(Y), resolution independent, same idiom as CropRectFraction (see
    // ImageScreen.kt's StereoImage, which multiplies this by the actual bitmap size for the live
    // preview crop, and ManualAlign.saveManualAlign, which does the same against the full-res file).
    var manualAlignOffsetX by mutableStateOf(0f)
        private set
    var manualAlignOffsetY by mutableStateOf(0f)
        private set

    // True while the crop tool is active for the current photo (see Exif3dInfoPanel's Crop
    // button) - from the click on "Crop" until Cancel/Save, covering both the drag-to-draw phase
    // (cropRect still null) and the review phase once a rectangle has been released. Mirrors
    // manualAlignMode's role of locking out the other tools/navigation while active - see Main.kt's
    // onPreviewKeyEvent.
    var cropMode by mutableStateOf(false)
        private set

    // The finalized crop rectangle (fraction of one eye-half's width/height, resolution
    // independent - see CropRectFraction), set once the user releases the drag in ImageScreen's
    // onCropDragEnd. Null while still drawing - drives both ImageScreen's "only show the crop area"
    // preview and AlignButtonsRow's Save button (only enabled once non-null).
    var cropRect by mutableStateOf<CropRectFraction?>(null)
        private set

    // True while the "Spot stereo issues" tool is active for the current photo (see
    // Exif3dInfoPanel's "Spot stereo issues" button) - from the click until Cancel/Save. Unlike
    // cropMode, multiple rectangles can be drawn while this stays true (see spotIssueRects) rather
    // than the tool locking into a review-only phase after the first one - see Main.kt's
    // onPreviewKeyEvent for the same lock-out-other-tools treatment as manualAlignMode/cropMode.
    var spotIssuesMode by mutableStateOf(false)
        private set

    // Rectangles drawn so far (fraction of one eye-half's width/height, resolution independent -
    // see IssueRectFraction), appended to in ImageScreen's onSpotIssueDragEnd. Drives both
    // ImageScreen's preview overlay and AlignButtonsRow's Save button (only enabled once non-empty).
    var spotIssueRects by mutableStateOf<List<IssueRectFraction>>(emptyList())
        private set

    // True while the "click matching points" manual-align variant is active (see
    // AlignButtonsRow's "Manual Align" submenu) - from the click on "Click matching points" until
    // Cancel/Save, same lock-out-other-tools treatment as manualAlignMode/cropMode/spotIssuesMode.
    var clickAlignMode by mutableStateOf(false)
        private set

    // The point clicked so far on each eye-half (fraction of that eye-half's own width/height -
    // see PointFraction), set/overwritten in setClickAlignPoint. Null until that half has been
    // clicked at least once. Drives both the pink-crosshair overlay and AlignButtonsRow's Save
    // button (only enabled once both are non-null).
    var clickAlignLeftPoint by mutableStateOf<PointFraction?>(null)
        private set
    var clickAlignRightPoint by mutableStateOf<PointFraction?>(null)
        private set

    // True while Shift is held during clickAlignMode (see Main.kt's onPreviewKeyEvent) - drives
    // ImageScreen's Shift-held alignment preview (see ClickAlign.Preview).
    var clickAlignPreviewActive by mutableStateOf(false)
        private set

    // The last computed Shift-preview (see AppViewModel.refreshClickAlignPreview) - null until
    // both points are set and a preview has actually been requested/computed.
    var clickAlignPreview by mutableStateOf<ClickAlign.Preview?>(null)
        private set

    // Non-null while the "Copy from image" tool is active (see AlignButtonsRow's "Copy from image"
    // submenu and CopyFromImage.kt) - from the submenu choice until Cancel/Save, same
    // lock-out-other-tools treatment as clickAlignMode. Its first phase reuses the click-align
    // points below (copyAlignLeftPoint/RightPoint); once both are set, the tool switches to painting.
    var copyDirection by mutableStateOf<CopyDirection?>(null)
        private set
    var copyAlignLeftPoint by mutableStateOf<PointFraction?>(null)
        private set
    var copyAlignRightPoint by mutableStateOf<PointFraction?>(null)
        private set

    // Created on the first brush stroke (its size depends on the decoded photo) - see paintCopyStroke.
    var copyMask by mutableStateOf<CopyFromImageMask?>(null)
        private set

    // True while Shift is held during the painting phase (see Main.kt's onPreviewKeyEvent): the
    // view temporarily drops the alignment to show the photo as it will be saved.
    var copyUnalignedPreview by mutableStateOf(false)
        private set

    // Brush radius as a fraction of the eye-half's height, changed with the mouse wheel while the
    // tool is active. Deliberately not cleared by resetAll, so the chosen size survives across photos.
    var copyBrushRadius by mutableStateOf(0.04f)
        private set

    val copyFromImageMode: Boolean get() = copyDirection != null

    /** True once both alignment points are set - the "Copy from image" tool then paints instead of placing points. */
    val copyPaintingPhase: Boolean get() = copyFromImageMode && copyAlignLeftPoint != null && copyAlignRightPoint != null

    /** The mutual-exclusion guard [startManualAlign]/[startCrop]/[startSpotIssues]/[startClickAlign]/[startCopyFromImage] all share. */
    val anyToolActive: Boolean get() = manualAlignMode || cropMode || spotIssuesMode || clickAlignMode || copyFromImageMode

    /** Clears every tool's pending state - called on every navigation so nothing carries over onto a different photo. */
    fun resetAll() {
        alignedPreview = null
        pendingAlignKind = null
        manualAlignMode = false
        manualAlignOffsetX = 0f
        manualAlignOffsetY = 0f
        cropMode = false
        cropRect = null
        spotIssuesMode = false
        spotIssueRects = emptyList()
        clickAlignMode = false
        clickAlignLeftPoint = null
        clickAlignRightPoint = null
        clickAlignPreviewActive = false
        clickAlignPreview = null
        copyDirection = null
        copyAlignLeftPoint = null
        copyAlignRightPoint = null
        copyMask = null
        copyUnalignedPreview = false
    }

    /** Applies a finished auto-align/correct-zoom attempt's result - see AppViewModel.performAutoAlign. */
    fun applyAlignedPreview(result: AutoAlign.AutoAlignResult?, kind: AlignKind?) {
        alignedPreview = result?.bitmap
        pendingAlignKind = if (result != null) kind else null
    }

    /** Discards the pending auto-align/correct-zoom preview without touching disk - same shape as
     *  [cancelManualAlign]/[cancelCrop]/[cancelSpotIssues]/[cancelClickAlign]. */
    fun cancelAlignedPreview() = resetAll()

    /** Enters manual-align mode for the currently shown photo - see Main.kt's arrow-key handling. */
    fun startManualAlign() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        manualAlignMode = true
        manualAlignOffsetX = 0f
        manualAlignOffsetY = 0f
    }

    /** Adds a fractional delta to the pending manual-align offset - see Main.kt's tick loop. */
    fun nudgeManualAlign(dx: Float, dy: Float) {
        if (!manualAlignMode) return
        manualAlignOffsetX += dx
        manualAlignOffsetY += dy
    }

    /** Discards the pending manual-align offset without touching disk. */
    fun cancelManualAlign() = resetAll()

    /** Enters crop mode for the currently shown photo - see Exif3dInfoPanel's Crop button. */
    fun startCrop() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        cropMode = true
        cropRect = null
    }

    /** Records the rectangle drawn in ImageScreen's onCropDragEnd, switching to the review phase. */
    fun finalizeCropRect(rect: CropRectFraction) {
        if (!cropMode) return
        cropRect = rect
    }

    /** Discards the crop tool (drawn rectangle or not) without touching disk. */
    fun cancelCrop() = resetAll()

    /** Enters "spot stereo issues" mode for the currently shown photo - see Exif3dInfoPanel's button. */
    fun startSpotIssues() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        spotIssuesMode = true
        spotIssueRects = emptyList()
    }

    /** Appends one rectangle drawn in ImageScreen's onSpotIssueDragEnd - the tool stays active so
     *  further rectangles can be drawn, unlike [finalizeCropRect]'s single-rectangle review phase. */
    fun addSpotIssueRect(rect: IssueRectFraction) {
        if (!spotIssuesMode) return
        spotIssueRects = spotIssueRects + rect
    }

    /** Discards the "spot stereo issues" tool (drawn rectangles or not) without touching disk. */
    fun cancelSpotIssues() = resetAll()

    /** Enters "click matching points" mode for the currently shown photo - see AlignButtonsRow's "Manual Align" submenu. */
    fun startClickAlign() {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        clickAlignMode = true
        clickAlignLeftPoint = null
        clickAlignRightPoint = null
        clickAlignPreviewActive = false
        clickAlignPreview = null
    }

    /** Records/moves the point for whichever eye-half was clicked - see ImageScreen's onRawClick
     *  wiring. Always overwrites, so re-clicking a half simply moves that half's point. */
    fun setClickAlignPoint(isLeft: Boolean, point: PointFraction) {
        if (!clickAlignMode) return
        if (isLeft) clickAlignLeftPoint = point else clickAlignRightPoint = point
    }

    /** Discards the click-align tool (points placed or not) without touching disk. */
    fun cancelClickAlign() = resetAll()

    /** Toggled by Main.kt's onPreviewKeyEvent while Shift is held during clickAlignMode. */
    fun updateClickAlignPreviewActive(active: Boolean) {
        clickAlignPreviewActive = active
    }

    /** Set by AppViewModel.refreshClickAlignPreview once the Shift-held preview has been computed. */
    fun applyClickAlignPreview(preview: ClickAlign.Preview?) {
        clickAlignPreview = preview
    }

    /** Enters the "Copy from image" tool for the currently shown photo - see AlignButtonsRow's "Copy from image" submenu. */
    fun startCopyFromImage(direction: CopyDirection) {
        if (anyToolActive) return
        alignedPreview = null
        pendingAlignKind = null
        copyDirection = direction
        copyAlignLeftPoint = null
        copyAlignRightPoint = null
        copyMask = null
        copyUnalignedPreview = false
    }

    /** Records/moves an alignment point during the tool's first phase - same overwrite semantics as [setClickAlignPoint]. */
    fun setCopyAlignPoint(isLeft: Boolean, point: PointFraction) {
        if (!copyFromImageMode || copyPaintingPhase) return
        if (isLeft) copyAlignLeftPoint = point else copyAlignRightPoint = point
    }

    /** Paints one brush segment into the target eye-half's mask - see CopyFromImageMask.stamp. */
    fun paintCopyStroke(eyeWidth: Int, eyeHeight: Int, from: PointFraction?, to: PointFraction) {
        if (!copyPaintingPhase) return
        val mask = copyMask ?: CopyFromImageMask(eyeWidth, eyeHeight).also { copyMask = it }
        mask.stamp(from, to, copyBrushRadius)
    }

    /** Mouse-wheel brush resizing: [factor] > 1 grows the brush. */
    fun scaleCopyBrush(factor: Float) {
        copyBrushRadius = (copyBrushRadius * factor).coerceIn(0.005f, 0.3f)
    }

    /** Toggled by Main.kt's onPreviewKeyEvent while Shift is held during the painting phase. */
    fun updateCopyUnalignedPreview(active: Boolean) {
        copyUnalignedPreview = active
    }

    /** Discards the "Copy from image" tool (points/painting or not) without touching disk. */
    fun cancelCopyFromImage() = resetAll()
}
