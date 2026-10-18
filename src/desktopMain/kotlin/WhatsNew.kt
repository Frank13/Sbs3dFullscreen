import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.whats_new_got_it
import sbs3dViewer.resources.whats_new_history_title
import sbs3dViewer.resources.whats_new_title
import sbs3dViewer.resources.whats_new_v111_item_1
import sbs3dViewer.resources.whats_new_v111_item_2
import sbs3dViewer.resources.whats_new_v111_item_3
import sbs3dViewer.resources.whats_new_v105_item_1
import sbs3dViewer.resources.whats_new_v105_item_2
import sbs3dViewer.resources.whats_new_v105_item_3
import sbs3dViewer.resources.whats_new_v104_item_1
import sbs3dViewer.resources.whats_new_v104_item_2
import sbs3dViewer.resources.whats_new_v104_item_3

data class WhatsNewRelease(val versionName: String, val items: List<StringResource>)

// Add new releases at the top (newest first). versionName must match gradle.properties' appVersion
// for the release to be announced - see the /whats-new-release skill.
val allWhatsNewReleases: List<WhatsNewRelease> = listOf(
    WhatsNewRelease(
        versionName = "1.1.1",
        items = listOf(
            Res.string.whats_new_v111_item_1,
            Res.string.whats_new_v111_item_2,
            Res.string.whats_new_v111_item_3,
        )
    ),
    WhatsNewRelease(
        versionName = "1.0.5",
        items = listOf(
            Res.string.whats_new_v105_item_1,
            Res.string.whats_new_v105_item_2,
            Res.string.whats_new_v105_item_3,
        )
    ),
    WhatsNewRelease(
        versionName = "1.0.4",
        items = listOf(
            Res.string.whats_new_v104_item_1,
            Res.string.whats_new_v104_item_2,
            Res.string.whats_new_v104_item_3,
        )
    ),
)

/**
 * Decides when the "What's new" dialog is shown: once after an update, never on a fresh install
 * (the consent dialog already greets those users), persisted like the other settings (Preferences.kt).
 */
object WhatsNewTracker {
    private val lastSeenVersionPref = StringPref("lastSeenWhatsNewVersion", "")

    /** Call once at launch: on a fresh install (no consent answer yet), nothing is "new" to announce. */
    fun onLaunch() {
        if (lastSeenVersionPref.load().isEmpty() && !Analytics.hasAnsweredConsent) {
            lastSeenVersionPref.save(appVersion)
        }
    }

    /** The release to announce now, or null. Marks it as seen, so it's returned only once. */
    fun releaseToShow(): WhatsNewRelease? {
        if (lastSeenVersionPref.load() == appVersion) return null
        lastSeenVersionPref.save(appVersion)
        return allWhatsNewReleases.firstOrNull { it.versionName == appVersion }
    }
}

@Composable
private fun WhatsNewReleaseSection(release: WhatsNewRelease, showTitle: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (showTitle) {
            Text(
                stringResource(Res.string.whats_new_title, release.versionName),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        release.items.forEach { item ->
            Row {
                Text("•", modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(item))
            }
        }
    }
}

/** Announces the latest [release] after an update. */
@Composable
fun WhatsNewDialog(release: WhatsNewRelease, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.whats_new_title, release.versionName)) },
        text = { WhatsNewReleaseSection(release, showTitle = false) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.whats_new_got_it)) } },
    )
}

/** Every release, newest first - opened from the About screen. */
@Composable
fun WhatsNewHistoryDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.whats_new_history_title)) },
        text = {
            val listState = rememberLazyListState()
            Box {
                LazyColumn(state = listState) {
                    items(allWhatsNewReleases) { release ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            WhatsNewReleaseSection(release, showTitle = true)
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
                // Offset by AlertDialog's 24dp content padding, like WelcomeScreen's settings dialog.
                if (listState.canScrollForward || listState.canScrollBackward) {
                    WindowsVerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).offset(x = 24.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.whats_new_got_it)) } },
    )
}
