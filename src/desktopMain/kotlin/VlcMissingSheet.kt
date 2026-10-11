import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.vlc_missing_dismiss
import sbs3dViewer.resources.vlc_missing_download
import sbs3dViewer.resources.vlc_missing_message
import sbs3dViewer.resources.vlc_missing_title

/** Explains that VLC is required for videos and music, with a link to download it - see AppViewModel.requireVlc. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VlcMissingSheet(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.errorContainer) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                    )
                }
                Text(stringResource(Res.string.vlc_missing_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(Res.string.vlc_missing_message), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.vlc_missing_dismiss)) }
                Button(onClick = {
                    uriHandler.openUri(Vlc.DownloadUrl)
                    Analytics.logEvent("vlc_download_clicked")
                }) { Text(stringResource(Res.string.vlc_missing_download)) }
            }
        }
    }
}
