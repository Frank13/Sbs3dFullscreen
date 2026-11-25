import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * The scaleX applied by [shrinkHorizontally] when active. Exposed so [StereoBlinkingCaretTextField]
 * can undo it when converting a [Modifier.cursor3DTextClickTarget] press position (window/screen
 * space, i.e. already visually squeezed) back into its [TextLayoutResult]'s own coordinate space
 * (laid out at full, pre-squeeze width - a graphicsLayer scale is a paint-time transform only, it
 * never affects measurement).
 */
const val ShrinkControlsScaleX = 0.5f

/**
 * Squeezes this composable's rendered width by 2 around [origin] when [active] - the actual
 * squeeze behind AppViewModel.shrinkControls, shared by every control it touches (the settings
 * menu, the raw/edited label, the info panel, and - via [Stereo3DAlertDialog] - every dialog box).
 * [origin] should match whichever corner/edge the control is visually pinned to (e.g. a
 * TopStart-anchored control wants `TransformOrigin(0f, 0f)`) so that edge stays fixed as the
 * control's own width changes (e.g. the settings menu expanding/collapsing) instead of the shrink
 * visibly sliding the control around. A no-op (not even a graphicsLayer allocated) when inactive.
 */
fun Modifier.shrinkHorizontally(active: Boolean, origin: TransformOrigin): Modifier =
    if (active) this.graphicsLayer { scaleX = ShrinkControlsScaleX; scaleY = 1f; transformOrigin = origin } else this

/**
 * Renders [title]/[text]/[confirmButton]/[dismissButton] as a plain material3 [AlertDialog] when
 * [shrinkControls] is off (unchanged, existing behavior), or as [Stereo3DAlertDialog] when it's on.
 * A plain AlertDialog opens as its own native OS window - a single copy that only appears in one
 * eye on a Half-SBS 3D monitor, the same "single overlay reads as pinned to one eye" problem
 * CLAUDE.md describes for every other overlay in this app - so under "shrink controls" every
 * ImageScreen/InfoPanel confirmation dialog goes through this instead, keeping each dialog's own
 * title/text/buttons content shared between both renderings.
 */
@Composable
fun AdaptiveAlertDialog(
    shrinkControls: Boolean,
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    if (shrinkControls) {
        Stereo3DAlertDialog(onDismissRequest = onDismissRequest, title = title, text = text, confirmButton = confirmButton, dismissButton = dismissButton)
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title,
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton ?: {},
        )
    }
}

/**
 * In-scene stand-in for material3's AlertDialog, used by [AdaptiveAlertDialog] instead of it under
 * "shrink controls": duplicated per half (like every other overlay - see CLAUDE.md's "Stereo (SBS)
 * display and depth-shift overlays" note) and squeezed horizontally by 2 via [shrinkHorizontally],
 * same treatment as the rest of the shrunk controls. Centered rather than pinned to a corner, so
 * unlike those it doesn't need a depth shift or a non-center [TransformOrigin].
 *
 * Its buttons must go through [Modifier.cursor3DClickTarget] (like every other clickable overlay
 * control in this app) rather than a plain native onClick - Stereo3DCursorHost consumes every
 * pointer event before a real click/focus gesture would ever reach them (an earlier attempt at
 * pausing that consumption while a dialog is shown never actually worked in practice, so this
 * dialog now plays by the same rules as everything else). A [text] slot containing a TextField
 * still needs REAL keyboard focus to be typed into - see [StereoIssueCommentDialog] for how that's
 * done (auto-focus on appear, no click required) - but doesn't need real pointer routing itself.
 */
@Composable
fun Stereo3DAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit = {},
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) { Stereo3DAlertDialogHalf(onDismissRequest, title, text, confirmButton, dismissButton) }
        Box(Modifier.fillMaxSize().weight(1f)) { Stereo3DAlertDialogHalf(onDismissRequest, title, text, confirmButton, dismissButton) }
    }
}

@Composable
private fun Stereo3DAlertDialogHalf(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)?,
) {
    // Focused (and kept focused - see the doc above) purely so Escape below has an owner to
    // dispatch through; a text() slot's own TextField (see StereoIssueCommentDialog) steals this
    // right back via its own auto-focus LaunchedEffect, which runs after this one.
    val cardFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { cardFocusRequester.requestFocus() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .shrinkHorizontally(active = true, origin = TransformOrigin.Center)
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2B2B2B))
                .padding(20.dp)
                // Centered, so - unlike a corner-pinned panel - it renders with no depth shift of
                // its own (see this file's class doc); the cursor should match that same 0 depth
                // while hovering the dialog, instead of the default floating-in-front CursorShiftPercent.
                .cursor3DDepthTarget(0f)
                .focusRequester(cardFocusRequester)
                .focusable()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        onDismissRequest()
                        true
                    } else {
                        false
                    }
                },
        ) {
            CompositionLocalProvider(
                LocalContentColor provides Color.White,
                LocalTextStyle provides TextStyle(color = Color.White, fontSize = 14.sp),
            ) {
                Column {
                    CompositionLocalProvider(
                        LocalTextStyle provides TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    ) {
                        title()
                    }
                    Spacer(Modifier.height(8.dp))
                    text()
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/**
 * Single-line-capable text field whose blinking caret is drawn by hand instead of relying on the
 * platform's own (internal, per-instance) blink animation. [Stereo3DAlertDialog] composes its
 * `text` slot twice, once per half - a plain TextField's caret can't be shown in both at once
 * (only one half ever holds real keyboard focus, and even if both did, their independent blink
 * animations would drift out of phase), so for the caret to appear identically and blink in
 * lockstep in both halves, [value] and [caretVisible] must be hoisted by the caller OUTSIDE that
 * `text` slot (shared, not re-created per half - see [StereoIssueCommentDialog]/[LegendTextDialog]).
 * Only the caret's pixel position is computed locally per call, from this instance's own
 * [TextLayoutResult] - each half lays out its own copy, but since both get the same text/width/
 * style, the two positions coincide.
 */
@Composable
fun StereoBlinkingCaretTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    caretVisible: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = LocalTextStyle.current,
    caretColor: Color = Color.Black,
    placeholder: @Composable (() -> Unit)? = null,
    // Only a minimum, no maxLines cap: a capped BasicTextField scrolls its text internally, and the
    // hand-drawn caret below is positioned from the unscrolled TextLayoutResult, so it would drift.
    minLines: Int = 1,
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    val horizontalPadding = 12.dp
    val verticalPadding = 8.dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = textStyle,
        minLines = minLines,
        cursorBrush = SolidColor(Color.Transparent),
        onTextLayout = { layoutResult = it },
        decorationBox = { innerTextField ->
            Box(
                Modifier
                    .background(Color.White, RoundedCornerShape(4.dp))
                    // See Modifier.cursor3DTextClickTarget's doc: under "shrink controls" a real
                    // press never reaches this field to place the caret under it, so this resolves
                    // it by hand from the press position + this instance's own TextLayoutResult.
                    .cursor3DTextClickTarget { localOffset ->
                        val layout = layoutResult ?: return@cursor3DTextClickTarget
                        // localOffset is in window/screen space, i.e. already squeezed by
                        // ShrinkControlsScaleX (this field is only ever composed inside a
                        // shrinkHorizontally(active = true, ...) ancestor - see the doc above and
                        // every call site) - divide back out before comparing against layout's own
                        // pre-squeeze coordinate space. Only X is squeezed (shrinkHorizontally
                        // leaves scaleY at 1f), so Y needs no such correction.
                        val unscaledX = localOffset.x / ShrinkControlsScaleX
                        val textLocal = Offset(
                            (unscaledX - with(density) { horizontalPadding.toPx() }).coerceIn(0f, layout.size.width.toFloat()),
                            (localOffset.y - with(density) { verticalPadding.toPx() }).coerceIn(0f, layout.size.height.toFloat()),
                        )
                        onValueChange(value.copy(selection = TextRange(layout.getOffsetForPosition(textLocal))))
                    }
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            ) {
                if (value.text.isEmpty()) placeholder?.invoke()
                innerTextField()
                // layoutResult only refreshes via onTextLayout, one composition after value does -
                // for that one frame it can still describe the PREVIOUS (shorter) text, so the new
                // selection offset must be clamped against that layout's own text, not value's.
                val cursorRect = layoutResult?.let { it.getCursorRect(value.selection.start.coerceIn(0, it.layoutInput.text.length)) }
                if (caretVisible && cursorRect != null) {
                    Box(
                        Modifier
                            .offset { IntOffset(cursorRect.left.roundToInt(), cursorRect.top.roundToInt()) }
                            .width(1.5.dp)
                            .height(with(density) { (cursorRect.bottom - cursorRect.top).toDp() })
                            .background(caretColor),
                    )
                }
            }
        },
    )
}
