import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.cancel_button
import sbs3dViewer.resources.feedback_no_email_send_anyway
import sbs3dViewer.resources.feedback_no_email_text
import sbs3dViewer.resources.feedback_no_email_title
import sbs3dViewer.resources.feedback_email_label
import sbs3dViewer.resources.feedback_explanation
import sbs3dViewer.resources.feedback_message_label
import sbs3dViewer.resources.feedback_send_button
import sbs3dViewer.resources.feedback_send_failed
import sbs3dViewer.resources.feedback_sent_text
import sbs3dViewer.resources.feedback_sent_title
import sbs3dViewer.resources.feedback_screen_title
import sbs3dViewer.resources.ok_button
import sbs3dViewer.resources.playlist_back_button
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Posts feedback to the 3dcamera.fr (Joomla com_contact) contact form in the background, with no
 * browser involved. Joomla's form needs a session cookie plus a per-session CSRF token (a hidden
 * input named by a 32-hex hash, value "1"), so [send] first GETs the form page with a fresh
 * cookie jar, copies every hidden input of `#contact-form`, then POSTs the visible fields.
 *
 * Joomla answers the POST with a 303 to a page whose `"joomla.messages":[{"<type>":[...]}]`
 * script option carries the outcome: type "message"/"success" on a sent email, "warning"/"error"
 * otherwise (e.g. "The security token did not match"). Anything else counts as a failure.
 */
object FeedbackSender {
    private const val ContactUrl = "https://3dcamera.fr/en/contact"

    /** Fixed subject, so feedback is easy to filter in the 3dcamera.fr inbox. Not localized on purpose. */
    private const val Subject = "SBS 3D Viewer Feedback"

    // The form requires a name and a syntactically valid email; used when the user leaves the
    // optional email blank (so replying is just not possible for that message).
    private const val SenderName = "SBS 3D Viewer user"
    private const val AnonymousEmail = "noreply@3dcamera.fr"

    private val hiddenInputRegex = Regex("""<input\s+type="hidden"\s+name="([^"]+)"\s+value="([^"]*)"""")
    private val messagesRegex = Regex(""""joomla\.messages":\[(.*?)\]\s*[,}]""")
    private val messageTypeRegex = Regex(""""(\w+)":\[""")

    /** True if the site confirmed the email was sent. Never throws (network/OneDrive-style rule: failures become false). */
    fun send(message: String, replyEmail: String): Boolean = try {
        val client = HttpClient.newBuilder()
            .cookieHandler(CookieManager())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

        val formPage = client.send(
            HttpRequest.newBuilder(URI(ContactUrl)).timeout(Duration.ofSeconds(30)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val form = formPage.substringAfter("id=\"contact-form\"", "").substringBefore("</form>")
        val hiddenFields = hiddenInputRegex.findAll(form).map { it.groupValues[1] to it.groupValues[2] }.toList()
        if (hiddenFields.none { it.first.matches(Regex("[0-9a-f]{32}")) }) return false // no CSRF token: page changed

        val fullMessage = message.trim() +
            "\n\n---\nSBS 3D Viewer $appVersion - ${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        val fields = listOf(
            "jform[contact_name]" to SenderName,
            "jform[contact_email]" to replyEmail.trim().ifEmpty { AnonymousEmail },
            "jform[contact_subject]" to Subject,
            "jform[contact_message]" to fullMessage,
        ) + hiddenFields
        val body = fields.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
        }

        val resultPage = client.send(
            HttpRequest.newBuilder(URI(ContactUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val messages = messagesRegex.find(resultPage)?.groupValues?.get(1).orEmpty()
        val types = messageTypeRegex.findAll(messages).map { it.groupValues[1].lowercase() }.toSet()
        types.isNotEmpty() && types.all { it == "message" || it == "success" }
    } catch (e: Exception) {
        // IOException (offline, DNS, TLS), HttpTimeoutException, InterruptedException, ...
        false
    }
}

/** Basic sanity check only - the site does the real validation. Blank is allowed (the email is optional). */
private fun isPlausibleEmail(email: String) = email.isBlank() || Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""").matches(email.trim())

private const val FeedbackMaxLength = 5000

/**
 * Opened from the welcome screen's settings dialog ("Send feedback", its first item): a multi-line
 * message field and a Send button that posts it through [FeedbackSender], without leaving the app.
 */
@Composable
fun FeedbackScreen(onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var showSentDialog by remember { mutableStateOf(false) }
    var showNoEmailDialog by remember { mutableStateOf(false) }

    fun send() {
        sending = true
        scope.launch {
            val sent = withContext(Dispatchers.IO) { FeedbackSender.send(message, email) }
            sending = false
            // Tracks usage and catches the site's form silently changing (success=false spikes).
            Analytics.logEvent("feedback_sent", mapOf("success" to sent, "with_email" to email.isNotBlank()))
            if (sent) {
                showSentDialog = true
            } else {
                snackbarHostState.showSnackbar(getString(Res.string.feedback_send_failed))
            }
        }
    }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(Res.string.feedback_screen_title),
        navigationIcon = {
            IconButton(onClick = onBack, enabled = !sending) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.playlist_back_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        actionsContent = {},
        bottomBar = {},
        snackbarHostState = snackbarHostState,
        screenContent = {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(Res.string.feedback_explanation))
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(FeedbackMaxLength) },
                    label = { Text(stringResource(Res.string.feedback_message_label)) },
                    enabled = !sending,
                    minLines = 8,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(Res.string.feedback_email_label)) },
                    enabled = !sending,
                    singleLine = true,
                    isError = !isPlausibleEmail(email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { if (email.isBlank()) showNoEmailDialog = true else send() },
                    enabled = !sending && message.isNotBlank() && isPlausibleEmail(email),
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.feedback_send_button))
                }
            }
        },
    )

    if (showNoEmailDialog) {
        AlertDialog(
            onDismissRequest = { showNoEmailDialog = false },
            title = { Text(stringResource(Res.string.feedback_no_email_title)) },
            text = { Text(stringResource(Res.string.feedback_no_email_text)) },
            confirmButton = {
                TextButton(onClick = { showNoEmailDialog = false; send() }) {
                    Text(stringResource(Res.string.feedback_no_email_send_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNoEmailDialog = false }) {
                    Text(stringResource(Res.string.cancel_button))
                }
            },
        )
    }

    if (showSentDialog) {
        AlertDialog(
            onDismissRequest = { showSentDialog = false; onBack() },
            title = { Text(stringResource(Res.string.feedback_sent_title)) },
            text = { Text(stringResource(Res.string.feedback_sent_text)) },
            confirmButton = {
                TextButton(onClick = { showSentDialog = false; onBack() }) {
                    Text(stringResource(Res.string.ok_button))
                }
            },
        )
    }
}
