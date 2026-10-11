import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.icon
import sbs3dViewer.resources.about_analytics_revoke_button
import sbs3dViewer.resources.about_analytics_status_allowed
import sbs3dViewer.resources.about_analytics_status_label
import sbs3dViewer.resources.about_analytics_status_revoked
import sbs3dViewer.resources.about_author_label
import sbs3dViewer.resources.about_license_label
import sbs3dViewer.resources.about_open_source_button
import sbs3dViewer.resources.about_open_source_dialog_title
import sbs3dViewer.resources.about_purpose_text
import sbs3dViewer.resources.about_screen_title
import sbs3dViewer.resources.about_version_label
import sbs3dViewer.resources.about_whats_new_button
import sbs3dViewer.resources.ok_button
import sbs3dViewer.resources.playlist_back_button

/** One open source dependency credited in the "Open source licenses" dialog below. */
private data class OpenSourceLibrary(val name: String, val license: String)

/**
 * Third-party libraries this app's desktop code (and the shared fr.camera3d.camera.* sources it
 * bundles from CameraSync3D) is built on, credited here as required by their respective licenses.
 * Kept as a plain hardcoded list rather than a Gradle-generated one - this project has too few
 * dependencies to justify a license-report plugin.
 */
private val openSourceLibraries = listOf(
    OpenSourceLibrary("Kotlin", "Apache License 2.0"),
    OpenSourceLibrary("kotlinx.coroutines", "Apache License 2.0"),
    OpenSourceLibrary("JetBrains Compose Multiplatform", "Apache License 2.0"),
    OpenSourceLibrary("Material Icons Extended", "Apache License 2.0"),
    OpenSourceLibrary("Jackson (jackson-module-kotlin, jackson-dataformat-yaml)", "Apache License 2.0"),
    OpenSourceLibrary("Coil", "Apache License 2.0"),
    OpenSourceLibrary("Reorderable (sh.calvin.reorderable)", "Apache License 2.0"),
    OpenSourceLibrary("Apache Commons Imaging", "Apache License 2.0"),
    OpenSourceLibrary("OpenCV", "Apache License 2.0"),
    OpenSourceLibrary("JNA", "Apache License 2.0"),
    OpenSourceLibrary("Skiko", "Apache License 2.0"),
    OpenSourceLibrary("AndroidX Lifecycle, SavedState, Collection (via Compose Multiplatform)", "Apache License 2.0"),
    OpenSourceLibrary("kotlinx.serialization, kotlinx-datetime, atomicfu", "Apache License 2.0"),
    OpenSourceLibrary("Okio", "Apache License 2.0"),
    OpenSourceLibrary("SnakeYAML Engine", "Apache License 2.0"),
    OpenSourceLibrary("Apache Commons IO, Apache Commons Lang", "Apache License 2.0"),
    OpenSourceLibrary("JetBrains Annotations, JSpecify, JetBrains Runtime API", "Apache License 2.0"),
    OpenSourceLibrary("OpenJDK runtime (Eclipse Temurin, bundled in the installer)", "GNU General Public License v2 with Classpath Exception"),
    OpenSourceLibrary("VLC media player (libVLC, installed separately)", "GNU Lesser General Public License v2.1 or later"),
)

/**
 * The app's version, read from the "app.version" system property set in build.gradle.kts'
 * jvmArgs (mirroring gradle.properties' appVersion) - no BuildConfig-generation step needed.
 */
val appVersion: String get() = System.getProperty("app.version") ?: "?"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showOpenSourceDialog by remember { mutableStateOf(false) }
    var showWhatsNewHistory by remember { mutableStateOf(false) }
    var analyticsConsentGranted by remember { mutableStateOf(Analytics.consentGranted) }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = stringResource(Res.string.about_screen_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
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
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Image(
                    painter = painterResource(Res.drawable.icon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .align(Alignment.CenterHorizontally),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.about_purpose_text))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.about_author_label))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.about_license_label))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.about_version_label, appVersion))
                Spacer(Modifier.height(24.dp))
                Button(onClick = { showOpenSourceDialog = true }) {
                    Text(stringResource(Res.string.about_open_source_button))
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showWhatsNewHistory = true }) {
                    Text(stringResource(Res.string.about_whats_new_button))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(
                        Res.string.about_analytics_status_label,
                        stringResource(
                            if (analyticsConsentGranted) Res.string.about_analytics_status_allowed
                            else Res.string.about_analytics_status_revoked
                        ),
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = analyticsConsentGranted,
                    onClick = {
                        Analytics.setConsent(false)
                        analyticsConsentGranted = false
                    },
                ) {
                    Text(stringResource(Res.string.about_analytics_revoke_button))
                }
                Spacer(Modifier.height(24.dp))
            }
        },
    )

    if (showWhatsNewHistory) {
        WhatsNewHistoryDialog(onDismiss = { showWhatsNewHistory = false })
    }

    if (showOpenSourceDialog) {
        AlertDialog(
            onDismissRequest = { showOpenSourceDialog = false },
            title = { Text(stringResource(Res.string.about_open_source_dialog_title)) },
            text = {
                LazyColumn {
                    items(openSourceLibraries) { library ->
                        Column {
                            Text(library.name, style = MaterialTheme.typography.bodyLarge)
                            Text(library.license, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOpenSourceDialog = false }) {
                    Text(stringResource(Res.string.ok_button))
                }
            },
        )
    }
}
