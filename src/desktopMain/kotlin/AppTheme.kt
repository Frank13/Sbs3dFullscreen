import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.RippleDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import fr.camera3d.camera.common.ui_components.LocalMouseScrollbar

// A bit more pronounced than the default hover state layer (8% content-color overlay), so
// hovering any button in the app reads as a clearly lighter highlight rather than a barely
// visible tint.
private const val ButtonHoverAlpha = 0.16f

/**
 * App-wide MaterialTheme wrapper. Overriding [LocalRippleConfiguration] here makes every
 * Material3 button (Button, IconButton, FilledIconButton, TextButton, OutlinedButton,
 * FloatingActionButton) and every plain `clickable()` in the app pick up the lighter hover
 * highlight automatically, since they all resolve their indication through this same
 * composition local - no need to pass hover colors/interaction sources at each call site.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme {
        val hoverRippleConfiguration = remember {
            RippleConfiguration(
                rippleAlpha = RippleAlpha(
                    draggedAlpha = RippleDefaults.RippleAlpha.draggedAlpha,
                    focusedAlpha = RippleDefaults.RippleAlpha.focusedAlpha,
                    hoveredAlpha = ButtonHoverAlpha,
                    pressedAlpha = RippleDefaults.RippleAlpha.pressedAlpha,
                )
            )
        }
        CompositionLocalProvider(
            LocalRippleConfiguration provides hoverRippleConfiguration,
            // Swaps the shared screens'/dialogs' touch-oriented chevron hint for the Windows scrollbar.
            LocalMouseScrollbar provides windowsMouseScrollbar,
            content = content,
        )
    }
}
