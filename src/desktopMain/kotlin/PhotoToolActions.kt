/**
 * Every callback of the per-photo edit tools (auto-align/correct zoom, manual and click align, crop,
 * spot stereo issues, delete), grouped so Main.kt -> ImageScreen -> InfoPanel -> InfoPanelHalf ->
 * AlignButtonsRow pass one object down instead of re-threading ~20 positional lambdas through each
 * level. A data class so InfoPanel can [copy] it with the submenu-collapsing variants of
 * [onStartManualAlign]/[onStartClickAlign]. Defaults to no-ops, like the individual parameters it
 * replaces, so previews/screenshot tests only pass what they need.
 */
data class PhotoToolActions(
    val onAutoAlign: () -> Unit = {},
    val onCorrectZoom: () -> Unit = {},
    val onSaveAligned: () -> Unit = {},
    val onCancelAligned: () -> Unit = {},
    val onStartManualAlign: () -> Unit = {},
    val onCancelManualAlign: () -> Unit = {},
    val onSaveManualAlign: () -> Unit = {},
    val onStartCrop: () -> Unit = {},
    val onCropRectFinalized: (CropRectFraction) -> Unit = {},
    val onCancelCrop: () -> Unit = {},
    val onSaveCrop: () -> Unit = {},
    val onStartSpotIssues: () -> Unit = {},
    val onSpotIssueRectAdded: (IssueRectFraction) -> Unit = {},
    val onCancelSpotIssues: () -> Unit = {},
    val onSaveSpotIssues: () -> Unit = {},
    val onStartClickAlign: () -> Unit = {},
    val onClickAlignPointSet: (isLeft: Boolean, PointFraction) -> Unit = { _, _ -> },
    val onCancelClickAlign: () -> Unit = {},
    val onSaveClickAlign: () -> Unit = {},
    val onRequestClickAlignPreview: () -> Unit = {},
    val onStartCopyFromImage: (CopyDirection) -> Unit = {},
    val onCopyAlignPointSet: (isLeft: Boolean, PointFraction) -> Unit = { _, _ -> },
    val onCopyPaintStroke: (eyeWidth: Int, eyeHeight: Int, from: PointFraction?, to: PointFraction) -> Unit = { _, _, _, _ -> },
    val onScaleCopyBrush: (Float) -> Unit = {},
    val onCancelCopyFromImage: () -> Unit = {},
    val onSaveCopyFromImage: () -> Unit = {},
    val onDeleteCurrentImage: () -> Unit = {},
    val onDeleteKeepingLeft: () -> Unit = {},
    val onDeleteKeepingRight: () -> Unit = {},
)

/**
 * Read-only snapshot of which edit tool is active and whether it has something to save - what
 * InfoPanel's AlignButtonsRow needs to decide which buttons to show/enable. Built by ImageScreen
 * from PhotoToolsState's values (see AppViewModel.photoTools).
 */
data class PhotoToolsUiState(
    val hasAlignedPreview: Boolean = false,
    val isAligning: Boolean = false,
    val manualAlignMode: Boolean = false,
    val hasManualOffset: Boolean = false,
    val cropMode: Boolean = false,
    val hasCropRect: Boolean = false,
    val spotIssuesMode: Boolean = false,
    val hasSpotIssueRects: Boolean = false,
    val clickAlignMode: Boolean = false,
    val hasClickAlignPoints: Boolean = false,
    val copyFromImageMode: Boolean = false,
    val hasCopyPainting: Boolean = false,
) {
    /** True while any tool owns the panel (only its Cancel/Save pair shows). */
    val anyToolActive: Boolean get() = manualAlignMode || cropMode || spotIssuesMode || clickAlignMode || copyFromImageMode || hasAlignedPreview
}
