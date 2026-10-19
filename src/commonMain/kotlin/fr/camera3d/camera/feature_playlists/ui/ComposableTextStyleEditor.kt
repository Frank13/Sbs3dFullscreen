package fr.camera3d.camera.feature_playlists.ui

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Reusable style editor for a playlist's title/subtitle/credits text (font, size, bold/italic/underline,
// color, background opacity, top/left/right(/bottom for credits) % bounding box), with a live preview reusing
// the same ComposableStyledPositionedText/ComposableCreditsRollView used at real playback. Same portable-strings convention as
// PlaylistFieldsComposables.kt/PlaylistScreenStrings.kt: every label is a String supplied by the
// caller instead of stringResource(R.string.x). Sync via tools/sync-from-android.ps1.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFormat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.PortableOkCancelDialog
import fr.camera3d.camera.feature_playlists.domain.TextFontFamily
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which of the playlist's texts (title-slide title/subtitle, credits) a given [ComposableEditTextStyleIcon] instance edits. */
enum class TextStyleTarget { TITLE, SUBTITLE, CREDITS }

private val PRESET_COLORS = listOf(
    "#FFFFFF", "#000000", "#FFEB3B", "#F44336", "#00E5FF", "#4CAF50", "#2196F3", "#FF9800",
)

data class TextStyleEditorStrings(
    val save: String,
    val cancel: String,
    val preview: String,
    val fontOptionsSection: String,
    val fontSize: String,
    val fontFamily: String,
    val bold: String,
    val italic: String,
    val underline: String,
    val colorOptionsSection: String,
    val textColor: String,
    val backgroundOpacity: String,
    val backgroundColor: String,
    val positionSection: String,
    val topPercent: String,
    val leftPercent: String,
    val rightPercent: String,
    val bottomPercent: String,
    val depthSection: String,
    val depthPercent: String,
    val fontFamilyDefault: String,
    val fontFamilySerif: String,
    val fontFamilySansSerif: String,
    val fontFamilyMonospace: String,
    val fontFamilyCursive: String,
) {
    fun fontFamilyLabel(family: TextFontFamily) = when (family) {
        TextFontFamily.DEFAULT -> fontFamilyDefault
        TextFontFamily.SERIF -> fontFamilySerif
        TextFontFamily.SANS_SERIF -> fontFamilySansSerif
        TextFontFamily.MONOSPACE -> fontFamilyMonospace
        TextFontFamily.CURSIVE -> fontFamilyCursive
    }
}

/**
 * An icon button that opens a dialog to edit the style of [editingTarget] (title, subtitle or credits).
 * [previewOverlay] is drawn on top of the preview (e.g. a logo the platform adds to its title slide),
 * [previewBackground] under it, instead of black (e.g. the last image the credits roll over).
 * For the title/subtitle, the dialog previews both together (the one being edited live, the other with
 * its currently-saved style) so the user can judge their relative position. For the credits, it previews
 * them alone, looping their roll at [creditsSpeedPercent] inside their outlined bounding box.
 */
@Composable
fun ComposableEditTextStyleIcon(
    label: String,
    titleText: String,
    titleStyle: TextStyleConfig,
    subtitleText: String,
    subtitleStyle: TextStyleConfig,
    editingTarget: TextStyleTarget,
    zPercent: Float,
    zDocumentation: String,
    strings: TextStyleEditorStrings,
    onSave: (TextStyleConfig) -> Boolean,
    onSaveZPercent: (Float) -> Boolean,
    previewOverlay: @Composable BoxScope.() -> Unit = {},
    previewBackground: @Composable BoxScope.() -> Unit = {},
    creditsText: String = "",
    creditsStyle: TextStyleConfig = TextStyleConfig.CREDITS_DEFAULT,
    creditsSpeedPercent: Float = DEFAULT_CREDITS_SPEED_PERCENT,
) {
    var openDialog by remember { mutableStateOf(false) }
    IconButton(
        onClick = { openDialog = true },
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape),
    ) {
        Icon(imageVector = Icons.Filled.TextFormat, contentDescription = label)
    }
    if (openDialog) {
        ComposableEditTextStyleDialog(
            label = label,
            titleText = titleText,
            titleStyle = titleStyle,
            subtitleText = subtitleText,
            subtitleStyle = subtitleStyle,
            creditsText = creditsText,
            creditsStyle = creditsStyle,
            creditsSpeedPercent = creditsSpeedPercent,
            editingTarget = editingTarget,
            zPercent = zPercent,
            zDocumentation = zDocumentation,
            strings = strings,
            previewOverlay = previewOverlay,
            previewBackground = previewBackground,
            onDismiss = { openDialog = false },
            onSave = { style, z ->
                val styleSaved = onSave(style)
                val zSaved = onSaveZPercent(z)
                if (styleSaved && zSaved) openDialog = false
            },
        )
    }
}

@Composable
private fun ComposableEditTextStyleDialog(
    label: String,
    titleText: String,
    titleStyle: TextStyleConfig,
    subtitleText: String,
    subtitleStyle: TextStyleConfig,
    creditsText: String,
    creditsStyle: TextStyleConfig,
    creditsSpeedPercent: Float,
    editingTarget: TextStyleTarget,
    zPercent: Float,
    zDocumentation: String,
    strings: TextStyleEditorStrings,
    previewOverlay: @Composable BoxScope.() -> Unit,
    previewBackground: @Composable BoxScope.() -> Unit,
    onDismiss: () -> Unit,
    onSave: (TextStyleConfig, Float) -> Unit,
) {
    val editedInitial = when (editingTarget) {
        TextStyleTarget.TITLE -> titleStyle
        TextStyleTarget.SUBTITLE -> subtitleStyle
        TextStyleTarget.CREDITS -> creditsStyle
    }
    var editedStyle by remember { mutableStateOf(editedInitial) }
    var editedZPercent by remember { mutableStateOf(zPercent) }
    val previewTitleStyle = if (editingTarget == TextStyleTarget.TITLE) editedStyle else titleStyle
    val previewSubtitleStyle = if (editingTarget == TextStyleTarget.SUBTITLE) editedStyle else subtitleStyle

    PortableOkCancelDialog(
        dialogTitle = label,
        dialogOkLabel = strings.save,
        dialogCancelLabel = strings.cancel,
        onDismissRequestCb = onDismiss,
        okCallback = { onSave(editedStyle, editedZPercent) },
        content = {
            ComposableEditTextStyleDialogContent(
                titleText = titleText,
                previewTitleStyle = previewTitleStyle,
                subtitleText = subtitleText,
                previewSubtitleStyle = previewSubtitleStyle,
                creditsText = if (editingTarget == TextStyleTarget.CREDITS) creditsText else null,
                creditsSpeedPercent = creditsSpeedPercent,
                editedStyle = editedStyle,
                editedZPercent = editedZPercent,
                zDocumentation = zDocumentation,
                strings = strings,
                previewOverlay = previewOverlay,
                previewBackground = previewBackground,
                onEditedStyleChange = { editedStyle = it },
                onEditedZPercentChange = { editedZPercent = it },
            )
        },
    )
}

/** Visual body of [ComposableEditTextStyleDialog]'s dialog, extracted so it can be previewed without a real Dialog window. */
@Composable
private fun ComposableEditTextStyleDialogContent(
    titleText: String,
    previewTitleStyle: TextStyleConfig,
    subtitleText: String,
    previewSubtitleStyle: TextStyleConfig,
    creditsText: String?, // not null when editing the credits style
    creditsSpeedPercent: Float,
    editedStyle: TextStyleConfig,
    editedZPercent: Float,
    zDocumentation: String,
    strings: TextStyleEditorStrings,
    previewOverlay: @Composable BoxScope.() -> Unit,
    previewBackground: @Composable BoxScope.() -> Unit,
    onEditedStyleChange: (TextStyleConfig) -> Unit,
    onEditedZPercentChange: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp),
    ) {
        Text(strings.preview, style = MaterialTheme.typography.labelLarge)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .clipToBounds(),
        ) {
            previewBackground()
            if (creditsText != null) {
                ComposableCreditsRollPreview(creditsText, editedStyle, creditsSpeedPercent)
            } else {
                ComposableStyledPositionedText(previewTitleStyle) { textStyle, modifier ->
                    Text(text = titleText, style = textStyle, modifier = modifier)
                }
                if (subtitleText.isNotEmpty()) {
                    ComposableStyledPositionedText(previewSubtitleStyle) { textStyle, modifier ->
                        Text(text = subtitleText, style = textStyle, modifier = modifier)
                    }
                }
                previewOverlay()
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionCard(strings.depthSection) {
            LabeledSlider(strings.depthPercent, editedZPercent, -3f..3f, unit = "%", decimals = 1, onValueChange = onEditedZPercentChange)
            Text(zDocumentation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        ComposableTextStyleFields(editedStyle, strings, withBottom = creditsText != null, onEditedStyleChange)
    }
}

/**
 * Credits roll of the style editor preview: the credits bounding box outlined, and the credits rolling in it,
 * restarting shortly after each complete roll (or immediately when the style changes).
 */
@Composable
private fun ComposableCreditsRollPreview(credits: String, style: TextStyleConfig, speedPercent: Float) {
    var rollCount by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var restartJob by remember { mutableStateOf<Job?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(x = maxWidth * style.leftPercent / 100f, y = maxHeight * style.topPercent / 100f)
                .size(
                    width = maxWidth * (1f - (style.leftPercent + style.rightPercent) / 100f).coerceAtLeast(0f),
                    height = maxHeight * (1f - (style.topPercent + style.bottomPercent) / 100f).coerceAtLeast(0f),
                )
                .border(1.dp, Color.Gray),
        )
    }
    key(rollCount, style) {
        val elapsedMs = rememberCreditsRollElapsedMs(isPaused = false)
        ComposableCreditsRollView(credits, style, speedPercent, elapsedMs, isPaused = false, onRemainingMs = { remainingMs ->
            restartJob?.cancel()
            restartJob = scope.launch {
                delay(remainingMs + 500)
                rollCount++
            }
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposableTextStyleFields(style: TextStyleConfig, strings: TextStyleEditorStrings, withBottom: Boolean, onChange: (TextStyleConfig) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(strings.fontOptionsSection) {
            LabeledSlider(strings.fontSize, style.fontSizePercent, 2f..30f, unit = "%", decimals = 1) { onChange(style.copy(fontSizePercent = it)) }

            Text(strings.fontFamily, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextFontFamily.entries.forEach { family ->
                    FilterChip(
                        selected = style.fontFamily == family,
                        onClick = { onChange(style.copy(fontFamily = family)) },
                        label = { Text(strings.fontFamilyLabel(family)) },
                    )
                }
            }

            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = style.bold,
                    onClick = { onChange(style.copy(bold = !style.bold)) },
                    label = { Text(strings.bold) },
                )
                FilterChip(
                    selected = style.italic,
                    onClick = { onChange(style.copy(italic = !style.italic)) },
                    label = { Text(strings.italic) },
                )
                FilterChip(
                    selected = style.underline,
                    onClick = { onChange(style.copy(underline = !style.underline)) },
                    label = { Text(strings.underline) },
                )
            }
        }

        SectionCard(strings.colorOptionsSection) {
            ComposableColorPicker(strings.textColor, style.colorHex) { onChange(style.copy(colorHex = it)) }

            LabeledSlider(strings.backgroundOpacity, style.backgroundOpacityPercent, 0f..100f, unit = "%") {
                onChange(style.copy(backgroundOpacityPercent = it))
            }
            ComposableColorPicker(strings.backgroundColor, style.backgroundColorHex) { onChange(style.copy(backgroundColorHex = it)) }
        }

        SectionCard(strings.positionSection) {
            LabeledSlider(strings.topPercent, style.topPercent, 0f..100f, unit = "%") { onChange(style.copy(topPercent = it)) }
            LabeledSlider(strings.leftPercent, style.leftPercent, 0f..100f, unit = "%") { onChange(style.copy(leftPercent = it)) }
            LabeledSlider(strings.rightPercent, style.rightPercent, 0f..100f, unit = "%") { onChange(style.copy(rightPercent = it)) }
            if (withBottom) {
                LabeledSlider(strings.bottomPercent, style.bottomPercent, 0f..100f, unit = "%") { onChange(style.copy(bottomPercent = it)) }
            }
        }
    }
}

/** Tile wrapping one section of [ComposableTextStyleFields], with a header identifying it. */
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun ComposableColorPicker(label: String, colorHex: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PRESET_COLORS.forEach { hex ->
                val selected = colorHex.equals(hex, ignoreCase = true)
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(hex, Color.White))
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                            shape = CircleShape,
                        )
                        .clickable { onChange(hex) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(parseHexColor(colorHex, Color.Transparent))
                        .border(width = 1.dp, color = Color.Gray, shape = CircleShape),
                )
                TextField(
                    value = colorHex,
                    onValueChange = onChange,
                    modifier = Modifier.width(110.dp),
                    singleLine = true,
                    placeholder = { Text("#RRGGBB") },
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    decimals: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.padding(top = 8.dp)) {
        val formattedValue = if (decimals <= 0) value.toInt().toString() else "%.${decimals}f".format(value)
        Text("$label: $formattedValue$unit", style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
