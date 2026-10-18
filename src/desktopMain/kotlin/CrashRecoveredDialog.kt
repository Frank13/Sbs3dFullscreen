import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.crash_recovered_message
import sbs3dViewer.resources.crash_recovered_ok
import sbs3dViewer.resources.crash_recovered_title

/**
 * Shown (from Main.kt) on the Welcome screen right after its window exception handler recovered
 * from a UI crash, so the sudden jump back to the home screen doesn't look like a random glitch.
 */
@Composable
fun CrashRecoveredDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(10.dp).size(24.dp),
                )
            }
        },
        title = { Text(stringResource(Res.string.crash_recovered_title)) },
        text = { Text(stringResource(Res.string.crash_recovered_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.crash_recovered_ok))
            }
        },
    )
}
