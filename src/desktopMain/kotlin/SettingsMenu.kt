import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.image_settings_menu_content_description

/**
 * Building blocks shared by ImageScreen's and VideoScreen's settings ("burger") menus - both put a
 * gear icon at the top start of each stereo half (see [SettingsMenuGear]), opening a panel (see
 * [SettingsMenuPanel]) of item/toggle rows. Screen-specific content (which rows, in which order)
 * stays in each screen's own file; only the shared chrome lives here.
 */

/** Logs a settings-menu row/switch click, keyed by a stable (non-localized) item id. */
fun trackMenuItem(item: String) {
    Analytics.logEvent("menu_item_click", mapOf("item" to item))
}

// Menu items lose apparent size when shrinkHorizontally's 0.5x scaleX squeezes the menu under
// "shrink controls" - bumped up only then (see SettingsMenuToggleRow/SettingsMenuItemRow) to
// compensate; full-size text already reads fine unshrunk. Same idea as InfoPanel.kt's
// ShrunkControlsFontSize.
val SettingsMenuShrunkFontSize = 18.sp

/**
 * The gear icon that opens/closes a settings menu panel, pinned at [shiftPercent] depth (0 = flat
 * against the screen glass, matching every other piece of UI chrome in this app - see
 * ImageScreen.kt's SettingsMenuShiftPercent doc). [shrinkControls] squeezes it under "shrink
 * controls" like every other panel (see ShrinkControls.kt); pass false for screens that don't wire
 * that setting up (e.g. VideoScreen).
 */
@Composable
fun SettingsMenuGear(shrinkControls: Boolean, shiftPercent: Float, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .shrinkHorizontally(shrinkControls, TransformOrigin(0f, 0f))
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .cursor3DClickTarget(onClick)
            .cursor3DDepthTarget(shiftPercent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = stringResource(Res.string.image_settings_menu_content_description),
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * The expandable panel opened by [SettingsMenuGear] - a rounded, semi-transparent Column sized to
 * its widest row (see the width(IntrinsicSize.Max) note this replaces in ImageScreen.kt's old
 * inline version) rather than stretching to the full half-screen width.
 */
@Composable
fun SettingsMenuPanel(shrinkControls: Boolean, shiftPercent: Float, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .width(IntrinsicSize.Max)
            .shrinkHorizontally(shrinkControls, TransformOrigin(0f, 0f))
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .focusProperties { canFocus = false }
            .cursor3DDepthTarget(shiftPercent),
    ) {
        content()
    }
}

/** One clickable text row inside a [SettingsMenuPanel] (e.g. "Next", "Exit fullscreen"). */
@Composable
fun SettingsMenuItemRow(label: String, shrinkControls: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .cursor3DClickTarget(onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = withArrowIcons(label),
            inlineContent = MenuArrowInlineContent,
            style = TextStyle(color = Color.White, fontSize = if (shrinkControls) SettingsMenuShrunkFontSize else 14.sp),
        )
    }
}

private const val ArrowRightId = "arrowRight"
private const val ArrowLeftId = "arrowLeft"

// Font fallback for glyph arrows (triangle + dash) picked different fonts per character, so the
// tail ended up misaligned vertically; Material icons are drawn as one shape and stay aligned.
private fun menuArrowInlineContent(id: String, icon: ImageVector) = id to InlineTextContent(
    Placeholder(1.4.em, 1.4.em, PlaceholderVerticalAlign.TextCenter),
) {
    Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.fillMaxSize())
}

private val MenuArrowInlineContent = mapOf(
    menuArrowInlineContent(ArrowRightId, Icons.AutoMirrored.Filled.ArrowForward),
    menuArrowInlineContent(ArrowLeftId, Icons.AutoMirrored.Filled.ArrowBack),
)

/** Replaces the plain "→" / "←" characters of a label with bold Material arrow icons, legible when fused in 3D. */
private fun withArrowIcons(label: String): AnnotatedString = buildAnnotatedString {
    label.forEach { c ->
        when (c) {
            '→' -> appendInlineContent(ArrowRightId, "→")
            '←' -> appendInlineContent(ArrowLeftId, "←")
            else -> append(c)
        }
    }
}

/** One label+[Switch] row inside a [SettingsMenuPanel] (e.g. "Keep best of each"). */
@Composable
fun SettingsMenuToggleRow(label: String, checked: Boolean, shrinkControls: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = TextStyle(color = Color.White, fontSize = if (shrinkControls) SettingsMenuShrunkFontSize else 14.sp),
            modifier = Modifier.width(220.dp),
        )
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .focusProperties { canFocus = false }
                .cursor3DClickTarget { onCheckedChange(!checked) },
        )
    }
}

/**
 * One [RadioButton]+label row of a single-choice submenu nested under a [SettingsMenuItemRow]
 * inside a [SettingsMenuPanel] (e.g. VideoScreen's "Audio output" device list) - indented under
 * its parent row and styled white like the panel's other rows, unlike [SettingsRadioRow], which
 * relies on a dialog's own content colors. The whole row is the click target, not just the dot.
 */
@Composable
fun SettingsMenuRadioRow(label: String, selected: Boolean, shrinkControls: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .cursor3DClickTarget(onClick)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = Color.White, unselectedColor = Color.White.copy(alpha = 0.7f)),
            modifier = Modifier.focusProperties { canFocus = false },
        )
        Text(text = label, style = TextStyle(color = Color.White, fontSize = if (shrinkControls) SettingsMenuShrunkFontSize else 14.sp))
    }
}

/**
 * One [RadioButton]+label row of a single-choice group inside a settings dialog's body (e.g.
 * ImageScreen's share picker). The whole row is the click target, not just the radio dot.
 */
@Composable
fun SettingsRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .cursor3DClickTarget(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.focusProperties { canFocus = false })
        Spacer(Modifier.width(4.dp))
        Text(label)
    }
}
