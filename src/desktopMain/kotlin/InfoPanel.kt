import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.camera3d.camera.shared.Desc3d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.align_auto_align_button
import sbs3dViewer.resources.align_correct_zoom_button
import sbs3dViewer.resources.align_finished_failed
import sbs3dViewer.resources.align_finished_success
import sbs3dViewer.resources.align_manual_align_button
import sbs3dViewer.resources.align_manual_align_click_option
import sbs3dViewer.resources.align_manual_align_keyboard_option
import sbs3dViewer.resources.align_save_button
import sbs3dViewer.resources.cancel_button
import sbs3dViewer.resources.click_align_preview_hint
import sbs3dViewer.resources.correct_zoom_finished_success
import sbs3dViewer.resources.crop_button
import sbs3dViewer.resources.delete_button
import sbs3dViewer.resources.delete_completely_button
import sbs3dViewer.resources.delete_confirm_dialog_message
import sbs3dViewer.resources.delete_confirm_dialog_title
import sbs3dViewer.resources.delete_keep_left_button
import sbs3dViewer.resources.delete_keep_right_button
import sbs3dViewer.resources.delete_raw_dialog_message
import sbs3dViewer.resources.delete_raw_dialog_title
import sbs3dViewer.resources.dialog_title_warning
import sbs3dViewer.resources.enter_the_picture_legend
import sbs3dViewer.resources.save_finished_exif_lost
import sbs3dViewer.resources.save_finished_failed
import sbs3dViewer.resources.save_finished_success
import sbs3dViewer.resources.share_failed_toast
import sbs3dViewer.resources.spot_stereo_issues_button
import sbs3dViewer.resources.exif_updated
import sbs3dViewer.resources.ic_add_comment
import sbs3dViewer.resources.ic_image_comment
import sbs3dViewer.resources.ic_text_comment
import sbs3dViewer.resources.image_likely_not_3d_toast
import sbs3dViewer.resources.ok_button
import sbs3dViewer.resources.outline_3d_24
import sbs3dViewer.resources.panel_base_measurement
import sbs3dViewer.resources.panel_device_count
import sbs3dViewer.resources.panel_focal_length
import sbs3dViewer.resources.panel_mode
import sbs3dViewer.resources.panel_trigger
import sbs3dViewer.resources.stereo_issue_warning_comment_hint
import sbs3dViewer.resources.stereo_issue_warning_comment_title
import sbs3dViewer.resources.stereo_issue_warning_comment_will_be_erased
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

// Same sign convention as Playlist's titleZPercent/subtitleZPercent (negative = toward the
// viewer): -1% makes the panel read as floating just in front of the screen rather than behind it.
private const val InfoPanelShiftPercent = -0.01f
private val ToastDuration = 3000.milliseconds
private val WarningColor = Color(0xFFFF9800)
private val OutlinedColor = Color(0xFF9E9E9E)
private val IconTextSpacing = 6.dp
// Buttons/text field lose apparent size when shrinkHorizontally's 0.5x scaleX squeezes the panel/
// dialog card under "shrink controls" - bumped up only then (see AlignButtonsRow, StereoIssueCommentDialog,
// LegendTextDialog) to compensate; full-size controls already read fine unshrunk.
private val ShrunkControlsFontSize = 18.sp

private data class Exif3dSummary(val desc: Desc3d, val copyright: String, val comment: String, val focalLengthIn35mmFilm: Int?)

// Mirrors Android's LegendIconType (fr.camera3d.camera.common.ui_components.LegendIcon): which
// bubble glyph to show for the legend button, derived the same way FullscreenViewerFragment
// derives Photo3d.legendType - a non-empty comment wins over the hasLegend flag. Named distinctly
// from GalleryScreen.kt's own file-private LegendIconType: both files share the default package,
// and two file-private top-level classes with the same name collide as JVM classes.
private enum class LegendButtonType { AddLegend, TextLegend, ImageLegend }

private fun legendButtonType(summary: Exif3dSummary): LegendButtonType = when {
    summary.comment.isNotEmpty() -> LegendButtonType.TextLegend
    summary.desc.hasLegend -> LegendButtonType.ImageLegend
    else -> LegendButtonType.AddLegend
}

/**
 * Stereo HUD toggled open/closed by pressing Shift or Ctrl over [ImageScreen]: the same 3D EXIF tags
 * FullscreenViewerFragment's characteristics panel shows on Android (favorite, stereo-issue
 * warning, legend, base/device/mode/trigger), duplicated on both halves of the screen and offset
 * by [InfoPanelShiftPercent] of half the screen width so it reads correctly in 3D - same
 * left/right-duplication technique as ComposablePortableTitleSlide.
 */
@Composable
fun InfoPanel(
    file: File,
    shrinkControls: Boolean = false,
    onDialogOpenChanged: (Boolean) -> Unit = {},
    onExifUpdated: () -> Unit = {},
    hasAlignedPreview: Boolean = false,
    isAligning: Boolean = false,
    manualAlignMode: Boolean = false,
    hasManualOffset: Boolean = false,
    cropMode: Boolean = false,
    hasCropRect: Boolean = false,
    spotIssuesMode: Boolean = false,
    hasSpotIssueRects: Boolean = false,
    clickAlignMode: Boolean = false,
    hasClickAlignPoints: Boolean = false,
    onAutoAlign: () -> Unit = {},
    onCorrectZoom: () -> Unit = {},
    onSaveAligned: () -> Unit = {},
    onCancelAligned: () -> Unit = {},
    onStartManualAlign: () -> Unit = {},
    onCancelManualAlign: () -> Unit = {},
    onSaveManualAlign: () -> Unit = {},
    onStartCrop: () -> Unit = {},
    onCancelCrop: () -> Unit = {},
    onSaveCrop: () -> Unit = {},
    onStartSpotIssues: () -> Unit = {},
    onCancelSpotIssues: () -> Unit = {},
    onSaveSpotIssues: () -> Unit = {},
    onStartClickAlign: () -> Unit = {},
    onCancelClickAlign: () -> Unit = {},
    onSaveClickAlign: () -> Unit = {},
    onDeleteCurrentImage: () -> Unit = {},
    onDeleteKeepingLeft: () -> Unit = {},
    onDeleteKeepingRight: () -> Unit = {},
) {
    // Read off the UI thread and show a spinner meanwhile: EXIF I/O is normally fast, but this
    // guards against a slow/network drive stalling the toggled-open HUD from appearing at all.
    var summary by remember(file) { mutableStateOf<Exif3dSummary?>(null) }
    LaunchedEffect(file) {
        summary = withContext(Dispatchers.IO) {
            Exif3dSummary(
                desc = Exif3d.get3dCameraCharacteristics(file) ?: Desc3d(),
                copyright = Exif.getExifCopyright(file),
                comment = Exif.getExifUserComment(file) ?: "",
                focalLengthIn35mmFilm = Exif.getExifFocalLengthIn35mmFilm(file),
            )
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val onToggleFavorite: () -> Unit = onToggleFavorite@{
        val current = summary ?: return@onToggleFavorite
        val newFavorite = !current.desc.favorite
        summary = current.copy(desc = current.desc.copy(favorite = newFavorite))
        Analytics.logEvent("favorite_toggle", mapOf("action" to if (newFavorite) "add" else "remove"))
        coroutineScope.launch(Dispatchers.IO) {
            Exif3d.setFavoriteInExif(file, newFavorite)
            withContext(Dispatchers.Main) { onExifUpdated() }
        }
    }
    val onSetWarning: (Boolean, String) -> Unit = onSetWarning@{ warning, comment ->
        val current = summary ?: return@onSetWarning
        val newDesc = if (warning) current.desc.copy(warning = true, warningComment = comment)
        else current.desc.copy(warning = false, warningComment = "")
        summary = current.copy(desc = newDesc)
        coroutineScope.launch(Dispatchers.IO) {
            Exif3d.setWarningInExif(file, warning, comment.takeIf { warning })
            withContext(Dispatchers.Main) { onExifUpdated() }
        }
    }
    val onSetLegend: (String) -> Unit = onSetLegend@{ text ->
        val current = summary ?: return@onSetLegend
        summary = current.copy(comment = text, desc = current.desc.copy(hasLegend = false))
        coroutineScope.launch(Dispatchers.IO) {
            Exif3d.setTextLegendInExif(file, text)
            withContext(Dispatchers.Main) { onExifUpdated() }
        }
    }

    // Mirrors Android's StereoIssueWarningToggleHandler: turning the warning on always asks for a
    // comment first, turning it off asks for confirmation only if a comment would be lost.
    var showWarningCommentDialog by remember(file) { mutableStateOf(false) }
    var showWarningEraseDialog by remember(file) { mutableStateOf(false) }
    val onWarningToggleRequest: (Boolean) -> Unit = { turnOn ->
        if (turnOn) {
            showWarningCommentDialog = true
        } else if (summary?.desc?.warningComment?.isNotEmpty() == true) {
            showWarningEraseDialog = true
        } else {
            onSetWarning(false, "")
        }
    }
    // No camera/OCR scan on desktop (unlike ScanLegendFragment's Acquire/Confirm/Crop steps), so
    // the legend button only ports the "type the legend text directly" path (EnterLegendScreen).
    var showLegendDialog by remember(file) { mutableStateOf(false) }

    // Whether AlignButtonsRow's "Manual Align" button is expanded into its two sub-choices
    // (keyboard nudge vs. click matching points) - hoisted here (rather than remembered separately
    // inside each duplicated InfoPanelHalf) so both halves agree on whether it's open, same idiom
    // as VideoScreen.kt's audioOutputExpanded nested-submenu precedent. Collapsed automatically by
    // onStartManualAlignFromSubmenu/onStartClickAlignFromSubmenu below the moment either choice is
    // picked, so it never reopens already-expanded next time the tool is started fresh.
    var manualAlignSubmenuExpanded by remember(file) { mutableStateOf(false) }
    val onManualAlignSubmenuToggle: () -> Unit = { manualAlignSubmenuExpanded = !manualAlignSubmenuExpanded }
    val onStartManualAlignFromSubmenu: () -> Unit = { manualAlignSubmenuExpanded = false; onStartManualAlign() }
    val onStartClickAlignFromSubmenu: () -> Unit = { manualAlignSubmenuExpanded = false; onStartClickAlign() }

    // An unedited stereo pair (GalleryScreen.kt's rawEditedLabel == "raw") gets a 3-way delete
    // choice (keep left eye as 2D / keep right eye as 2D / delete completely) instead of a plain
    // confirm, since discarding it loses a pair that was never derived from anything else - an
    // "_edited"/"_editedN" photo, by contrast, still has its raw original elsewhere, so a plain
    // confirm is enough.
    val isRawPhoto = remember(file) { rawEditedLabel(file) == "raw" }
    var showDeleteConfirmDialog by remember(file) { mutableStateOf(false) }
    var showDeleteRawChoiceDialog by remember(file) { mutableStateOf(false) }
    val onDeleteRequest: () -> Unit = {
        if (isRawPhoto) showDeleteRawChoiceDialog = true else showDeleteConfirmDialog = true
    }

    // Reported up so Main.kt's onPreviewKeyEvent stops hijacking Escape/Space/arrow keys/"A" as
    // app-wide shortcuts while any of the dialogs below is open (see ImageScreen's
    // onDialogShownChanged doc) - under "shrink controls" they render in-scene rather than as a
    // separate native window, so those keys would otherwise never reach the dialog itself.
    val anyDialogOpen = showWarningCommentDialog || showWarningEraseDialog || showLegendDialog ||
        showDeleteConfirmDialog || showDeleteRawChoiceDialog
    LaunchedEffect(anyDialogOpen) { onDialogOpenChanged(anyDialogOpen) }
    // Toggling the info panel closed (Shift/Ctrl) while a dialog is open removes InfoPanel from
    // composition without ever seeing anyDialogOpen go back to false - reset explicitly so
    // Main.kt doesn't keep passing keys through forever.
    DisposableEffect(Unit) { onDispose { onDialogOpenChanged(false) } }

    // Hidden (rather than left showing underneath) while a dialog is open: its icons/switches
    // still register real cursor3DClickTarget click targets, and since those targets are resolved
    // by the same registry the dialog's own buttons use, an icon happening to sit under/near the
    // dialog card could otherwise still catch a click meant for it.
    if (!anyDialogOpen) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val halfWidth = maxWidth / 2
            val shift = halfWidth * InfoPanelShiftPercent
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f)) {
                    InfoPanelHalf(file, summary, offsetX = -shift / 2, shrinkControls, onToggleFavorite, onWarningToggleRequest, { showLegendDialog = true }, hasAlignedPreview, isAligning, manualAlignMode, hasManualOffset, cropMode, hasCropRect, spotIssuesMode, hasSpotIssueRects, clickAlignMode, hasClickAlignPoints, manualAlignSubmenuExpanded, onManualAlignSubmenuToggle, onAutoAlign, onCorrectZoom, onSaveAligned, onCancelAligned, onStartManualAlignFromSubmenu, onCancelManualAlign, onSaveManualAlign, onStartCrop, onCancelCrop, onSaveCrop, onStartSpotIssues, onCancelSpotIssues, onSaveSpotIssues, onStartClickAlignFromSubmenu, onCancelClickAlign, onSaveClickAlign, onDeleteRequest)
                }
                Box(Modifier.fillMaxSize().weight(1f)) {
                    InfoPanelHalf(file, summary, offsetX = shift / 2, shrinkControls, onToggleFavorite, onWarningToggleRequest, { showLegendDialog = true }, hasAlignedPreview, isAligning, manualAlignMode, hasManualOffset, cropMode, hasCropRect, spotIssuesMode, hasSpotIssueRects, clickAlignMode, hasClickAlignPoints, manualAlignSubmenuExpanded, onManualAlignSubmenuToggle, onAutoAlign, onCorrectZoom, onSaveAligned, onCancelAligned, onStartManualAlignFromSubmenu, onCancelManualAlign, onSaveManualAlign, onStartCrop, onCancelCrop, onSaveCrop, onStartSpotIssues, onCancelSpotIssues, onSaveSpotIssues, onStartClickAlignFromSubmenu, onCancelClickAlign, onSaveClickAlign, onDeleteRequest)
                }
            }
        }
    }

    if (showWarningCommentDialog) {
        StereoIssueCommentDialog(
            shrinkControls = shrinkControls,
            initialComment = summary?.desc?.warningComment ?: "",
            onDismiss = { showWarningCommentDialog = false },
            onConfirm = { comment ->
                showWarningCommentDialog = false
                onSetWarning(true, comment)
            },
        )
    }
    if (showWarningEraseDialog) {
        AdaptiveAlertDialog(
            shrinkControls = shrinkControls,
            onDismissRequest = { showWarningEraseDialog = false },
            title = { Text(stringResource(Res.string.dialog_title_warning)) },
            text = { Text(stringResource(Res.string.stereo_issue_warning_comment_will_be_erased)) },
            confirmButton = {
                val onClick = { showWarningEraseDialog = false; onSetWarning(false, "") }
                TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) {
                    Text(stringResource(Res.string.ok_button))
                }
            },
            dismissButton = {
                val onClick = { showWarningEraseDialog = false }
                TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }
    if (showLegendDialog) {
        LegendTextDialog(
            shrinkControls = shrinkControls,
            initialText = summary?.comment ?: "",
            onDismiss = { showLegendDialog = false },
            onConfirm = { text ->
                showLegendDialog = false
                onSetLegend(text)
            },
        )
    }
    if (showDeleteConfirmDialog) {
        AdaptiveAlertDialog(
            shrinkControls = shrinkControls,
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(stringResource(Res.string.delete_confirm_dialog_title)) },
            text = { Text(stringResource(Res.string.delete_confirm_dialog_message)) },
            confirmButton = {
                val onClick = { showDeleteConfirmDialog = false; onDeleteCurrentImage() }
                TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) {
                    Text(stringResource(Res.string.delete_button))
                }
            },
            dismissButton = {
                val onClick = { showDeleteConfirmDialog = false }
                TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }
    if (showDeleteRawChoiceDialog) {
        AdaptiveAlertDialog(
            shrinkControls = shrinkControls,
            onDismissRequest = { showDeleteRawChoiceDialog = false },
            title = { Text(stringResource(Res.string.delete_raw_dialog_title)) },
            text = { Text(stringResource(Res.string.delete_raw_dialog_message)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    val onKeepLeft = { showDeleteRawChoiceDialog = false; onDeleteKeepingLeft() }
                    TextButton(onClick = onKeepLeft, modifier = Modifier.cursor3DClickTarget(onKeepLeft)) {
                        Text(stringResource(Res.string.delete_keep_left_button))
                    }
                    val onKeepRight = { showDeleteRawChoiceDialog = false; onDeleteKeepingRight() }
                    TextButton(onClick = onKeepRight, modifier = Modifier.cursor3DClickTarget(onKeepRight)) {
                        Text(stringResource(Res.string.delete_keep_right_button))
                    }
                    val onDeleteAll = { showDeleteRawChoiceDialog = false; onDeleteCurrentImage() }
                    TextButton(onClick = onDeleteAll, modifier = Modifier.cursor3DClickTarget(onDeleteAll)) {
                        Text(stringResource(Res.string.delete_completely_button))
                    }
                }
            },
            dismissButton = {
                val onClick = { showDeleteRawChoiceDialog = false }
                TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }
}

@Composable
private fun StereoIssueCommentDialog(shrinkControls: Boolean, initialComment: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var fieldValue by remember(initialComment) { mutableStateOf(TextFieldValue(initialComment)) }
    // Ticked once here, not inside the text() slot below, so both stereo halves - which each
    // invoke that slot independently, see Stereo3DAlertDialog - read the exact same blink phase
    // instead of drifting apart (see StereoBlinkingCaretTextField's doc).
    var caretVisible by remember { mutableStateOf(true) }
    LaunchedEffect(shrinkControls) {
        if (!shrinkControls) return@LaunchedEffect
        while (true) {
            delay(500)
            caretVisible = !caretVisible
        }
    }
    AdaptiveAlertDialog(
        shrinkControls = shrinkControls,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.stereo_issue_warning_comment_title)) },
        text = {
            // Auto-focused on appear rather than requiring a click - see Stereo3DAlertDialogHalf's
            // doc: under "shrink controls" a real click/focus gesture can't reliably reach this
            // TextField (Stereo3DCursorHost consumes pointer events for the registry-routed model
            // every other overlay control uses), so typing has to work without one.
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            if (shrinkControls) {
                // A plain TextField here would only ever show a caret in whichever of the two
                // duplicated halves happens to end up with real focus - see
                // StereoBlinkingCaretTextField's doc.
                StereoBlinkingCaretTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    caretVisible = caretVisible,
                    modifier = Modifier.focusRequester(focusRequester),
                    textStyle = LocalTextStyle.current.copy(color = Color.Black, fontSize = ShrunkControlsFontSize),
                    placeholder = {
                        Text(stringResource(Res.string.stereo_issue_warning_comment_hint), color = Color.Black.copy(alpha = 0.6f), fontSize = ShrunkControlsFontSize)
                    },
                )
            } else {
                // The dialog card sets LocalTextStyle's color to white (see Stereo3DAlertDialogHalf)
                // for its title/labels against the dark card background, but TextField's own input
                // area is a light field regardless of that surrounding color. TextFieldDefaults.colors
                // alone can't fix this: Material3's TextField resolves its text/placeholder color via
                // `textStyle.color.takeOrElse { colorsParam }`, and since the ambient LocalTextStyle
                // already carries an explicit (non-Unspecified) white color, that wins over the colors
                // param every time - so LocalTextStyle itself has to be overridden here too.
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = Color.Black)) {
                    TextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        placeholder = { Text(stringResource(Res.string.stereo_issue_warning_comment_hint)) },
                        modifier = Modifier.focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.Black,
                            unfocusedTextColor = Color.Black,
                            focusedPlaceholderColor = Color.Black,
                            unfocusedPlaceholderColor = Color.Black,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            val onClick = { onConfirm(fieldValue.text.trim()) }
            val buttonFontSize = if (shrinkControls) ShrunkControlsFontSize else TextUnit.Unspecified
            TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) { Text(stringResource(Res.string.ok_button), fontSize = buttonFontSize) }
        },
        dismissButton = {
            val buttonFontSize = if (shrinkControls) ShrunkControlsFontSize else TextUnit.Unspecified
            TextButton(onClick = onDismiss, modifier = Modifier.cursor3DClickTarget(onDismiss)) { Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize) }
        },
    )
}

@Composable
private fun LegendTextDialog(shrinkControls: Boolean, initialText: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var fieldValue by remember(initialText) { mutableStateOf(TextFieldValue(initialText)) }
    // See StereoIssueCommentDialog's matching comment.
    var caretVisible by remember { mutableStateOf(true) }
    LaunchedEffect(shrinkControls) {
        if (!shrinkControls) return@LaunchedEffect
        while (true) {
            delay(500)
            caretVisible = !caretVisible
        }
    }
    AdaptiveAlertDialog(
        shrinkControls = shrinkControls,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.enter_the_picture_legend)) },
        text = {
            // See StereoIssueCommentDialog's matching comment.
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            if (shrinkControls) {
                StereoBlinkingCaretTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    caretVisible = caretVisible,
                    modifier = Modifier.focusRequester(focusRequester),
                    textStyle = LocalTextStyle.current.copy(color = Color.Black, fontSize = ShrunkControlsFontSize),
                )
            } else {
                CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(color = Color.Black)) {
                    TextField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        modifier = Modifier.focusRequester(focusRequester),
                        colors = TextFieldDefaults.colors(focusedTextColor = Color.Black, unfocusedTextColor = Color.Black),
                    )
                }
            }
        },
        confirmButton = {
            val onClick = { onConfirm(fieldValue.text.trim()) }
            val buttonFontSize = if (shrinkControls) ShrunkControlsFontSize else TextUnit.Unspecified
            TextButton(onClick = onClick, modifier = Modifier.cursor3DClickTarget(onClick)) { Text(stringResource(Res.string.ok_button), fontSize = buttonFontSize) }
        },
        dismissButton = {
            val buttonFontSize = if (shrinkControls) ShrunkControlsFontSize else TextUnit.Unspecified
            TextButton(onClick = onDismiss, modifier = Modifier.cursor3DClickTarget(onDismiss)) { Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize) }
        },
    )
}

@Composable
private fun InfoPanelHalf(
    file: File,
    summary: Exif3dSummary?,
    offsetX: Dp,
    shrinkControls: Boolean,
    onToggleFavorite: () -> Unit,
    onWarningToggleRequest: (Boolean) -> Unit,
    onLegendClick: () -> Unit,
    hasAlignedPreview: Boolean,
    isAligning: Boolean,
    manualAlignMode: Boolean,
    hasManualOffset: Boolean,
    cropMode: Boolean,
    hasCropRect: Boolean,
    spotIssuesMode: Boolean,
    hasSpotIssueRects: Boolean,
    clickAlignMode: Boolean,
    hasClickAlignPoints: Boolean,
    manualAlignSubmenuExpanded: Boolean,
    onManualAlignSubmenuToggle: () -> Unit,
    onAutoAlign: () -> Unit,
    onCorrectZoom: () -> Unit,
    onSaveAligned: () -> Unit,
    onCancelAligned: () -> Unit,
    onStartManualAlign: () -> Unit,
    onCancelManualAlign: () -> Unit,
    onSaveManualAlign: () -> Unit,
    onStartCrop: () -> Unit,
    onCancelCrop: () -> Unit,
    onSaveCrop: () -> Unit,
    onStartSpotIssues: () -> Unit,
    onCancelSpotIssues: () -> Unit,
    onSaveSpotIssues: () -> Unit,
    onStartClickAlign: () -> Unit,
    onCancelClickAlign: () -> Unit,
    onSaveClickAlign: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, bottom = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.BottomStart,
    ) {
        Box(
            modifier = Modifier
                .shrinkHorizontally(shrinkControls, TransformOrigin(0f, 1f))
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(12.dp)
                .cursor3DDepthTarget(InfoPanelShiftPercent),
        ) {
            if (summary == null) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                return@Box
            }
            Column {
                // Hidden while cropping, spotting stereo issues, manually aligning (either
                // variant), or reviewing a pending auto-align/correct-zoom preview: the panel then
                // only needs to show Cancel/Save (see AlignButtonsRow below) - the favorite/warning/
                // legend icons, 3D info, comment and copyright are just noise while the user is
                // focused on the rectangle/nudge/points/preview, and this row is width-constrained
                // to half the screen (see Exif3dInfoPanel's BoxWithConstraints) so dropping it also
                // gives AlignButtonsRow more room.
                if (!cropMode && !manualAlignMode && !spotIssuesMode && !clickAlignMode && !hasAlignedPreview) {
                    InfoPanelContent(summary, onToggleFavorite, onWarningToggleRequest, onLegendClick)
                }
                AlignButtonsRow(shrinkControls, hasAlignedPreview, isAligning, manualAlignMode, hasManualOffset, cropMode, hasCropRect, spotIssuesMode, hasSpotIssueRects, clickAlignMode, hasClickAlignPoints, manualAlignSubmenuExpanded, onManualAlignSubmenuToggle, onAutoAlign, onCorrectZoom, onSaveAligned, onCancelAligned, onStartManualAlign, onCancelManualAlign, onSaveManualAlign, onStartCrop, onCancelCrop, onSaveCrop, onStartSpotIssues, onCancelSpotIssues, onSaveSpotIssues, onStartClickAlign, onCancelClickAlign, onSaveClickAlign, onDeleteRequest)
                ShadowedText(file.name, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun AlignButtonsRow(
    shrinkControls: Boolean,
    hasAlignedPreview: Boolean,
    isAligning: Boolean,
    manualAlignMode: Boolean,
    hasManualOffset: Boolean,
    cropMode: Boolean,
    hasCropRect: Boolean,
    spotIssuesMode: Boolean,
    hasSpotIssueRects: Boolean,
    clickAlignMode: Boolean,
    hasClickAlignPoints: Boolean,
    manualAlignSubmenuExpanded: Boolean,
    onManualAlignSubmenuToggle: () -> Unit,
    onAutoAlign: () -> Unit,
    onCorrectZoom: () -> Unit,
    onSaveAligned: () -> Unit,
    onCancelAligned: () -> Unit,
    onStartManualAlign: () -> Unit,
    onCancelManualAlign: () -> Unit,
    onSaveManualAlign: () -> Unit,
    onStartCrop: () -> Unit,
    onCancelCrop: () -> Unit,
    onSaveCrop: () -> Unit,
    onStartSpotIssues: () -> Unit,
    onCancelSpotIssues: () -> Unit,
    onSaveSpotIssues: () -> Unit,
    onStartClickAlign: () -> Unit,
    onCancelClickAlign: () -> Unit,
    onSaveClickAlign: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // Material3's default disabled Button colors (a low-alpha tint of MaterialTheme's onSurface,
    // meant for a light/dark *theme surface* background) go nearly invisible against this panel's
    // own Color.Black.copy(alpha = 0.5f) background - MaterialTheme here is the default light
    // scheme, so disabled text ends up dark-on-dark. Every Button below gets this override instead,
    // so a disabled Save/Cancel/etc. still reads as clearly present (dim, but visible), matching
    // "greyed out, not hidden" for every tool's Save button while nothing's been drawn/moved yet.
    val panelButtonColors = ButtonDefaults.buttonColors(
        disabledContainerColor = Color.White.copy(alpha = 0.15f),
        disabledContentColor = Color.White.copy(alpha = 0.6f),
    )
    // Red container so the destructive delete tool doesn't read as just another align/crop action -
    // same "greyed out, not hidden" disabled treatment as panelButtonColors while a task is running.
    val deleteButtonColors = ButtonDefaults.buttonColors(
        containerColor = Color(0xFFB71C1C),
        contentColor = Color.White,
        disabledContainerColor = Color.White.copy(alpha = 0.15f),
        disabledContentColor = Color.White.copy(alpha = 0.6f),
    )
    val buttonFontSize = if (shrinkControls) ShrunkControlsFontSize else TextUnit.Unspecified
    // Tracked so ManualAlignSubmenuPanel can be widened to at least this, rather than only ever
    // hugging its own (often narrower) row text - see its doc.
    val density = LocalDensity.current
    var manualAlignButtonWidth by remember { mutableStateOf(0.dp) }
    // The "no tool active" buttons (Auto Align/Correct Zoom/Manual Align/Crop/Spot stereo
    // issues/Delete) all additionally hide while the "Manual Align" submenu is expanded - the row
    // would otherwise show a confusing mix of the submenu's own two choices sitting alongside a
    // handful of unrelated buttons, all styled identically. The submenu toggle button itself stays
    // visible (it isn't gated by this) so the user can click it again to collapse. They also hide
    // once [hasAlignedPreview] is true (an auto-align/correct-zoom result is pending review) -
    // that state gets its own Cancel/Save pair below, same "only the two relevant buttons" idiom as
    // clickAlignMode/cropMode/spotIssuesMode/manualAlignMode.
    val hideOtherButtons = !manualAlignMode && !cropMode && !spotIssuesMode && !clickAlignMode &&
        !manualAlignSubmenuExpanded && !hasAlignedPreview
    Column {
        // FlowRow (not Row) + widthIn(max = ...) so this wraps onto multiple lines instead of
        // overflowing/clipping past the panel's edge - there are up to 6 buttons in the "no tool
        // active" state (Auto Align/Correct Zoom/Manual Align/Crop/Spot stereo issues/Delete), too
        // many to fit on one line within the half-screen-constrained panel (see Exif3dInfoPanel's
        // BoxWithConstraints). Item-to-item spacing (both within and between lines) comes from
        // horizontalArrangement/verticalArrangement, so the individual Spacer(8.dp) calls the old
        // single-Row layout needed between buttons are gone.
        FlowRow(
            modifier = Modifier.padding(top = 8.dp).widthIn(max = 340.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // canFocus = false for the same reason as the favorite icon above: this HUD can be
            // toggled closed by pressing Shift/Ctrl again, so a click stealing keyboard focus would
            // break Escape/arrow key handling on Main.kt's root Box as soon as it does.
            if (hideOtherButtons) {
                // Disabled for the whole duration of a running auto-align/correct-zoom/save task
                // (see AppViewModel.isAligning) so a click can't re-trigger or overlap it.
                Button(
                    onClick = onAutoAlign,
                    enabled = !isAligning,
                    colors = panelButtonColors,
                    modifier = Modifier.focusProperties { canFocus = false }
                        .cursor3DClickTarget { if (!isAligning) onAutoAlign() },
                ) {
                    Text(stringResource(Res.string.align_auto_align_button), fontSize = buttonFontSize)
                }
                Button(
                    onClick = onCorrectZoom,
                    enabled = !isAligning,
                    colors = panelButtonColors,
                    modifier = Modifier.focusProperties { canFocus = false }
                        .cursor3DClickTarget { if (!isAligning) onCorrectZoom() },
                ) {
                    Text(stringResource(Res.string.align_correct_zoom_button), fontSize = buttonFontSize)
                }
            }
            // "Manual Align" opens a real dropdown menu (see SettingsMenuPanel below) of two
            // choices - keyboard nudge vs. click matching points (see ClickAlign.kt) - rather than
            // starting a tool directly. The chevron (flipped while open) is the same "this expands"
            // affordance a standard dropdown uses, so it doesn't read as just another action button.
            if (!manualAlignMode && !cropMode && !spotIssuesMode && !clickAlignMode && !hasAlignedPreview) {
                Button(
                    onClick = onManualAlignSubmenuToggle,
                    enabled = !isAligning,
                    colors = panelButtonColors,
                    modifier = Modifier.focusProperties { canFocus = false }
                        .cursor3DClickTarget { if (!isAligning) onManualAlignSubmenuToggle() }
                        .onSizeChanged { manualAlignButtonWidth = with(density) { it.width.toDp() } },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(Res.string.align_manual_align_button), fontSize = buttonFontSize)
                        Icon(
                            imageVector = if (manualAlignSubmenuExpanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                        )
                    }
                }
            }
        // Cancel/Save pair for a pending auto-align/correct-zoom preview - unlike every other tool's
        // pair, there's no in-progress drawing/nudging phase to gate Save on: by the time
        // [hasAlignedPreview] is true a full result already exists, so Save is enabled as soon as
        // it appears (only [isAligning] - a save already running - disables it). The button itself
        // doesn't render at all beforehand (see [hideOtherButtons]'s doc), matching "hidden, not
        // disabled" instead of the old always-visible-but-disabled Save button.
        if (hasAlignedPreview) {
            Button(
                onClick = onCancelAligned,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelAligned() },
            ) {
                Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize)
            }
            Button(
                onClick = onSaveAligned,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onSaveAligned() },
            ) {
                Text(stringResource(Res.string.align_save_button), fontSize = buttonFontSize)
            }
        }
        // Cancel/Save pair for the in-progress manual nudge - arrow keys move the right half while
        // this is up (see Main.kt's onPreviewKeyEvent); Save is only enabled once something has
        // actually moved, so an accidental click can't write out an identical duplicate file.
        if (manualAlignMode) {
            Button(
                onClick = onCancelManualAlign,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelManualAlign() },
            ) {
                Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize)
            }
            Button(
                onClick = onSaveManualAlign,
                enabled = hasManualOffset && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasManualOffset && !isAligning) onSaveManualAlign() },
            ) {
                Text(stringResource(Res.string.align_save_button), fontSize = buttonFontSize)
            }
        }
        // Cancel/Save pair for the click-matching-points variant - Save is only enabled once both
        // eye-halves have a recorded point (see PhotoToolsState.clickAlignLeftPoint/RightPoint),
        // same "nothing to commit yet" gating as the other tools' pairs.
        if (clickAlignMode) {
            Button(
                onClick = onCancelClickAlign,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelClickAlign() },
            ) {
                Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize)
            }
            Button(
                onClick = onSaveClickAlign,
                enabled = hasClickAlignPoints && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasClickAlignPoints && !isAligning) onSaveClickAlign() },
            ) {
                Text(stringResource(Res.string.align_save_button), fontSize = buttonFontSize)
            }
        }
        if (hideOtherButtons) {
            Button(
                onClick = onStartCrop,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onStartCrop() },
            ) {
                Text(stringResource(Res.string.crop_button), fontSize = buttonFontSize)
            }
        }
        // Cancel/Save pair for the crop tool - Save is only enabled once a rectangle has actually
        // been drawn (see PhotoToolsState.cropRect), same "nothing to commit yet" gating as the
        // manual-align pair above.
        if (cropMode) {
            Button(
                onClick = onCancelCrop,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelCrop() },
            ) {
                Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize)
            }
            Button(
                onClick = onSaveCrop,
                enabled = hasCropRect && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasCropRect && !isAligning) onSaveCrop() },
            ) {
                Text(stringResource(Res.string.align_save_button), fontSize = buttonFontSize)
            }
        }
        if (hideOtherButtons) {
            Button(
                onClick = onStartSpotIssues,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onStartSpotIssues() },
            ) {
                Text(stringResource(Res.string.spot_stereo_issues_button), fontSize = buttonFontSize)
            }
        }
        // Cancel/Save pair for "Spot stereo issues" - Save is only enabled once at least one
        // rectangle has been drawn (see PhotoToolsState.spotIssueRects), same "nothing to commit yet"
        // gating as the crop/manual-align pairs above. Unlike the crop pair, the tool stays active
        // (rather than moving to review-only) after each rectangle so further ones can be drawn.
        if (spotIssuesMode) {
            Button(
                onClick = onCancelSpotIssues,
                enabled = !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onCancelSpotIssues() },
            ) {
                Text(stringResource(Res.string.cancel_button), fontSize = buttonFontSize)
            }
            Button(
                onClick = onSaveSpotIssues,
                enabled = hasSpotIssueRects && !isAligning,
                colors = panelButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (hasSpotIssueRects && !isAligning) onSaveSpotIssues() },
            ) {
                Text(stringResource(Res.string.align_save_button), fontSize = buttonFontSize)
            }
        }
        if (hideOtherButtons) {
            Button(
                onClick = onDeleteRequest,
                enabled = !isAligning,
                colors = deleteButtonColors,
                modifier = Modifier.focusProperties { canFocus = false }
                    .cursor3DClickTarget { if (!isAligning) onDeleteRequest() },
            ) {
                Text(stringResource(Res.string.delete_button), fontSize = buttonFontSize)
            }
        }
        if (isAligning) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
        }
        // The real dropdown itself - see ManualAlignSubmenuPanel's doc for why this doesn't just
        // reuse SettingsMenuPanel/SettingsMenuItemRow verbatim. Rendered right below the FlowRow
        // (outside it) so it's never subject to FlowRow's own line-wrapping.
        if (manualAlignSubmenuExpanded) {
            ManualAlignSubmenuPanel(manualAlignButtonWidth) {
                ManualAlignSubmenuRow(stringResource(Res.string.align_manual_align_keyboard_option), shrinkControls) {
                    onStartManualAlign()
                }
                ManualAlignSubmenuRow(stringResource(Res.string.align_manual_align_click_option), shrinkControls) {
                    onStartClickAlign()
                }
            }
        }
    }
}

// Deliberately a different color (not the same 50%-black every other panel/dialog in this app
// uses) plus a visible border, rather than just reusing SettingsMenuPanel's exact look - the user
// found the plain-button version of this submenu hard to tell apart from AlignButtonsRow's other
// buttons, so this panel needs to visibly read as "a distinct floating menu", not blend in.
private val ManualAlignSubmenuBackgroundColor = Color(0xFF263238).copy(alpha = 0.92f)
private val ManualAlignSubmenuBorderColor = Color.White.copy(alpha = 0.45f)
private val ManualAlignSubmenuFontSize = 18.sp
private val ManualAlignSubmenuShrunkFontSize = 22.sp

/**
 * The "Manual Align" dropdown's own panel chrome - same rounded/cursor3DDepthTarget shape as
 * [SettingsMenuPanel], but with [ManualAlignSubmenuBackgroundColor]/a border instead of that
 * panel's plain 50%-black, so it's visually unmistakable as a separate menu rather than more of
 * AlignButtonsRow's buttons.
 *
 * Unlike [SettingsMenuPanel] (its own independent per-half overlay, pinned flat at 0%), this panel
 * is nested inside [InfoPanelHalf], so it inherits that panel's own [InfoPanelShiftPercent] depth
 * rather than sitting at its own - [cursor3DDepthTarget] below is registered at that same
 * [InfoPanelShiftPercent], not a separate value, so the cursor doesn't snap to a different depth
 * than the menu it's actually hovering.
 *
 * Sized via a custom [Layout] rather than a plain `Column.width(IntrinsicSize.Max)`
 * (SettingsMenuPanel's approach): width is `max(minWidth, widest row's own natural width)`, not
 * just the latter - so the dropdown reads as clearly anchored under the "Manual Align" button it
 * opens from instead of shrinking narrower than it, however short the option labels are.
 * `IntrinsicSize.Max` always sizes to exactly the content's intrinsic width, discarding any outer
 * `widthIn(min = ...)`, so that combination can't express "at least X, else wrap content" here.
 *
 * No `shrinkHorizontally` call of its own (unlike [SettingsMenuPanel], which needs one): this panel
 * is nested inside [InfoPanelHalf]'s own already-`shrinkHorizontally`-wrapped Box, so it inherits
 * that squeeze already - adding a second one here would compound to a 0.25x scale instead of 0.5x,
 * reading as visibly narrower than the "Manual Align" button (which only inherits the single
 * ancestor squeeze) under "shrink controls".
 */
@Composable
private fun ManualAlignSubmenuPanel(minWidth: Dp, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(ManualAlignSubmenuBackgroundColor)
            .border(1.5.dp, ManualAlignSubmenuBorderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .focusProperties { canFocus = false }
            .cursor3DDepthTarget(InfoPanelShiftPercent),
    ) { measurables, constraints ->
        val naturalWidth = measurables.maxOf { it.maxIntrinsicWidth(constraints.maxHeight) }
        val targetWidth = maxOf(naturalWidth, minWidth.roundToPx()).coerceAtMost(constraints.maxWidth)
        val rowConstraints = Constraints.fixedWidth(targetWidth)
        val placeables = measurables.map { it.measure(rowConstraints) }
        val totalHeight = placeables.sumOf { it.height }
        layout(targetWidth, totalHeight) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

/**
 * One clickable row inside [ManualAlignSubmenuPanel] - bold and bigger than
 * [SettingsMenuItemRow]'s plain text, per the same "make it unmistakably a menu" rationale.
 *
 * Deliberately no `fillMaxWidth()` here: [ManualAlignSubmenuPanel]'s own [Layout] already measures
 * this Row with a tight (fixed) width constraint matching the panel's full computed width, and a
 * fixed constraint alone is enough for Row to report/occupy that exact width (fillMaxWidth is only
 * needed to *ask* for the parent's available width, not to fill an already-fixed one) - and
 * dropping it keeps this Row's own [maxIntrinsicWidth] query (used by that same [Layout] to size
 * the panel to its widest row) reporting this row's true natural width, not muddied by a `Fill`
 * modifier's own intrinsic-measurement behavior.
 */
@Composable
private fun ManualAlignSubmenuRow(label: String, shrinkControls: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .cursor3DClickTarget(onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (shrinkControls) ManualAlignSubmenuShrunkFontSize else ManualAlignSubmenuFontSize,
            ),
        )
    }
}

@Composable
private fun InfoPanelContent(
    summary: Exif3dSummary,
    onToggleFavorite: () -> Unit,
    onWarningToggleRequest: (Boolean) -> Unit,
    onLegendClick: () -> Unit,
) {
    val desc = summary.desc
    val has3dData = desc.baseMm != -1 || desc.triggerMode.isNotEmpty() || desc.extMode.isNotEmpty() ||
        desc.deviceCount != 2 || summary.focalLengthIn35mmFilm != null
    Column(horizontalAlignment = Alignment.Start) {
        // Mirrors Android's FavoriteAndStereoIssueWarningAndLegendIconBar. clickable() makes an
        // icon focusable by default; since this HUD can be toggled closed by pressing Shift/Ctrl
        // again, letting a click steal keyboard focus away from Main.kt's root Box breaks all key
        // handling (Escape included) once it does and this panel - along with the now-focused
        // icon - leaves composition.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (desc.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = null,
                tint = if (desc.favorite) Color.Red else Color.White,
                modifier = Modifier.size(28.dp).focusProperties { canFocus = false }
                    .clickable(onClick = onToggleFavorite)
                    .cursor3DClickTarget(onToggleFavorite),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = if (desc.warning) WarningColor else OutlinedColor,
                modifier = Modifier.size(28.dp).focusProperties { canFocus = false }
                    .clickable { onWarningToggleRequest(!desc.warning) }
                    .cursor3DClickTarget { onWarningToggleRequest(!desc.warning) },
            )
            Spacer(Modifier.width(4.dp))
            val legendPainter = when (legendButtonType(summary)) {
                LegendButtonType.AddLegend -> painterResource(Res.drawable.ic_add_comment)
                LegendButtonType.TextLegend -> painterResource(Res.drawable.ic_text_comment)
                LegendButtonType.ImageLegend -> painterResource(Res.drawable.ic_image_comment)
            }
            Icon(
                painter = legendPainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp).focusProperties { canFocus = false }
                    .clickable(onClick = onLegendClick)
                    .cursor3DClickTarget(onLegendClick),
            )
        }
        if (has3dData) {
            val threeDIconSize = 22.dp
            val lines = listOfNotNull(
                desc.baseMm.takeIf { it != -1 }?.let { stringResource(Res.string.panel_base_measurement, "%.1f".format(it / 10f)) },
                stringResource(Res.string.panel_device_count, desc.deviceCount),
                desc.extMode.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.panel_mode, it) },
                desc.triggerMode.takeIf { it.isNotEmpty() && desc.deviceCount != 1 }?.let { stringResource(Res.string.panel_trigger, it) },
                summary.focalLengthIn35mmFilm?.let { stringResource(Res.string.panel_focal_length, it) },
            )
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(Res.drawable.outline_3d_24),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(end = IconTextSpacing).size(threeDIconSize),
                    )
                    ShadowedText(lines.first())
                }
                lines.drop(1).forEach { line -> ShadowedText(line, modifier = Modifier.padding(start = threeDIconSize + IconTextSpacing)) }
            }
        }
        if (summary.comment.isNotEmpty()) {
            val commentIconSize = 22.dp
            val commentLines = summary.comment.split("\n")
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_text_comment),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.padding(end = IconTextSpacing).offset(y = 3.dp).size(commentIconSize),
                    )
                    ShadowedText(commentLines.first())
                }
                commentLines.drop(1).forEach { line -> ShadowedText(line, modifier = Modifier.padding(start = commentIconSize + IconTextSpacing)) }
            }
        } else if (desc.hasLegend) {
            Icon(painter = painterResource(Res.drawable.ic_image_comment), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        if (desc.warning && desc.warningComment.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = WarningColor,
                    modifier = Modifier.padding(end = IconTextSpacing).size(22.dp),
                )
                ShadowedText(desc.warningComment)
            }
        }
        if (summary.copyright.isNotEmpty()) {
            ShadowedText("© ${summary.copyright}")
        }
    }
}

@Composable
private fun ShadowedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = Color.White,
            fontSize = 22.sp,
            shadow = Shadow(color = Color.Black, blurRadius = 3f, offset = Offset(2f, 2f)),
        ),
    )
}

/**
 * Shared shell for the toasts below: fades [content] in when [trigger] changes to a non-null
 * value, holds it for 1.5s, then fades out - duplicated on both halves and offset by [shiftPercent]
 * of half the screen width like [InfoPanel] so it reads correctly in 3D (same sign
 * convention: negative brings it toward the viewer). [trigger] doubles as both the "is something
 * to show" flag and the [LaunchedEffect] restart key, so passing a fresh (non-equal) value
 * re-flashes the toast even if the outcome is the same as last time (e.g. two failures in a row).
 */
@Composable
private fun <T : Any> StereoToast(trigger: T?, shiftPercent: Float = InfoPanelShiftPercent, content: @Composable () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (trigger == null) {
            visible = false
            return@LaunchedEffect
        }
        visible = true
        delay(ToastDuration)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val halfWidth = maxWidth / 2
            val shift = halfWidth * shiftPercent
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f)) { StereoToastHalf(offsetX = -shift / 2, content) }
                Box(Modifier.fillMaxSize().weight(1f)) { StereoToastHalf(offsetX = shift / 2, content) }
            }
        }
    }
}

@Composable
private fun StereoToastHalf(offsetX: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().offset(x = offsetX),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            content()
        }
    }
}

/**
 * Brief confirmation flashed after an EXIF write (e.g. toggling favorite) completes. Bump
 * [updateToken] (e.g. increment a counter) each time a write finishes to (re)start the fade-out
 * timer via [StereoToast]. Unlike [InfoPanel] this is meant to survive the panel being
 * toggled closed, so callers should host [updateToken] outside the Shift/Ctrl-toggled composition
 * (see ImageScreen.kt).
 */
@Composable
fun ExifUpdatedToast(updateToken: Int, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = updateToken.takeIf { it != 0 }, shiftPercent = shiftPercent) {
        Text(stringResource(Res.string.exif_updated), color = Color.White, fontSize = 18.sp)
    }
}

/**
 * Brief confirmation flashed after an auto-align/correct-zoom attempt (triggered from either the
 * Exif3dInfoPanel buttons or the "A" keyboard shortcut in Main.kt) finishes, success or failure -
 * bump AppViewModel.alignToast's token to (re)start the fade-out timer via [StereoToast]. Same
 * hosting rationale as [ExifUpdatedToast]: it must survive the panel being toggled closed, so
 * callers host it outside the Shift/Ctrl-toggled composition (see ImageScreen.kt).
 */
@Composable
fun AlignResultToast(toast: AlignToast?, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = toast, shiftPercent = shiftPercent) {
        val zoomScale = toast?.zoomScale
        val rotationDegrees = toast?.rotationDegrees
        val message = if (toast?.success != true) {
            stringResource(Res.string.align_finished_failed)
        } else if (zoomScale != null && rotationDegrees != null) {
            stringResource(
                Res.string.correct_zoom_finished_success,
                "%.1f".format(zoomScale * 100f),
                "%.1f".format(rotationDegrees),
            )
        } else {
            stringResource(Res.string.align_finished_success)
        }
        Text(message, color = if (toast?.success == true) Color.White else WarningColor, fontSize = 18.sp)
    }
}

/**
 * Brief confirmation flashed after a "Save" button click (see AlignButtonsRow's onSaveAligned)
 * finishes, success or failure - bump AppViewModel.saveToast's token to (re)start the fade-out
 * timer via [StereoToast]. Same hosting rationale as [ExifUpdatedToast]: it must survive the
 * panel being toggled closed, so callers host it outside the Shift/Ctrl-toggled composition (see
 * ImageScreen.kt). A successful save whose EXIF copy failed (see AutoAlign.SavedFile.exifError)
 * gets its own message/warning color, including the underlying exception's message, rather than
 * reading as a plain, fully-successful save - the saved photo is real, but its original metadata
 * (in particular the Exif3d "3D characteristics") didn't make it across.
 */
@Composable
fun SaveResultToast(toast: SaveToast?, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = toast, shiftPercent = shiftPercent) {
        val exifError = toast?.exifError
        val message = when {
            toast?.success != true -> stringResource(Res.string.save_finished_failed)
            exifError != null -> stringResource(Res.string.save_finished_exif_lost, exifError)
            else -> stringResource(Res.string.save_finished_success)
        }
        val fullySuccessful = toast?.success == true && exifError == null
        Text(message, color = if (fullySuccessful) Color.White else WarningColor, fontSize = 18.sp)
    }
}

/**
 * Brief error flash after the settings menu's "Share" dialog fails to hand the photo off to the
 * default email program (see AppViewModel.performShare/ShareToast's doc - success and a cancelled
 * compose window don't trigger this, only a real MAPI failure). Same hosting rationale as
 * [ExifUpdatedToast].
 */
@Composable
fun ShareResultToast(toast: ShareToast?, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = toast, shiftPercent = shiftPercent) {
        Text(stringResource(Res.string.share_failed_toast), color = WarningColor, fontSize = 18.sp)
    }
}

/**
 * Brief warning flashed once per file when [SideBySideLikeliness] (synced from the Camera 3D
 * Android app - see ImageScreen.kt's notLikely3DToken) judges a freshly-loaded JPEG unlikely to be
 * a side-by-side stereo photo. [trigger] is a token rather than a Boolean so navigating back to a
 * previously-flagged photo re-flashes the toast (see StereoToast's doc on trigger identity).
 */
@Composable
fun NotLikely3DToast(trigger: Int, shiftPercent: Float = InfoPanelShiftPercent) {
    StereoToast(trigger = trigger.takeIf { it != 0 }, shiftPercent = shiftPercent) {
        Text(stringResource(Res.string.image_likely_not_3d_toast), color = WarningColor, fontSize = 18.sp)
    }
}

/**
 * Shown for as long as [visible] holds (unlike every other toast here, which flashes for a fixed
 * duration off a trigger token - see [StereoToast]'s doc): while Shift is held during click-align
 * mode but the preview isn't ready yet (see ImageScreen's clickAlignPreviewActive/clickAlignPreview
 * and AppViewModel.refreshClickAlignPreview, which re-decodes the full-resolution file off the UI
 * thread and so isn't instant), this tells the user their key press registered instead of it just
 * looking like nothing happened. Disappears the moment the preview itself appears (or Shift is
 * released), so it never overlaps the preview it's warning about.
 */
@Composable
fun ClickAlignPreviewHintToast(visible: Boolean, shiftPercent: Float = InfoPanelShiftPercent) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val halfWidth = maxWidth / 2
            val shift = halfWidth * shiftPercent
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().weight(1f)) {
                    StereoToastHalf(offsetX = -shift / 2) {
                        Text(stringResource(Res.string.click_align_preview_hint), color = Color.White, fontSize = 18.sp)
                    }
                }
                Box(Modifier.fillMaxSize().weight(1f)) {
                    StereoToastHalf(offsetX = shift / 2) {
                        Text(stringResource(Res.string.click_align_preview_hint), color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
