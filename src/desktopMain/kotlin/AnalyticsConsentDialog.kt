import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.analytics_consent_accept
import sbs3dViewer.resources.analytics_consent_decline
import sbs3dViewer.resources.analytics_consent_message
import sbs3dViewer.resources.analytics_consent_title

/**
 * Shown once (from Main.kt, gated on Analytics.hasAnsweredConsent) before any usage data is
 * ever sent - no onDismissRequest handling, since either button is a valid, explicit answer and
 * there's no sensible "dismiss without choosing" outcome.
 */
@Composable
fun AnalyticsConsentDialog(onAnswered: (granted: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(Res.string.analytics_consent_title)) },
        text = { Text(stringResource(Res.string.analytics_consent_message)) },
        confirmButton = {
            TextButton(onClick = { onAnswered(true) }) {
                Text(stringResource(Res.string.analytics_consent_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswered(false) }) {
                Text(stringResource(Res.string.analytics_consent_decline))
            }
        },
    )
}
