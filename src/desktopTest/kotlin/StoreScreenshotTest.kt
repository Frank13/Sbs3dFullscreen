import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import java.awt.Frame
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Generates Microsoft Store listing screenshots by rendering app screens off-screen (via
 * runDesktopComposeUiTest/captureToImage - no real window, no OS screen capture) and dumping them
 * as PNGs under build/store-screenshots. Reproducible and exact-resolution, unlike a hand-taken
 * screenshot - see the "generate Store screenshots" conversation for why this approach was chosen
 * over driving the real app UI.
 *
 * Run with `./gradlew.bat desktopTest --tests StoreScreenshotTest`.
 *
 * RULE: every listing screenshot must be produced for every language in [StoreScreenshotLanguages],
 * at [StoreScreenshotWidth]x[StoreScreenshotHeight]. Don't call runDesktopComposeUiTest directly to
 * add a new one - add a `@Test` that calls [captureScreenshot] instead, which is the one place that
 * rule is enforced; it can't be skipped per-screen that way. To change the target languages or
 * resolution for every screenshot at once, edit only the companion object constants below.
 */
@OptIn(ExperimentalTestApi::class)
class StoreScreenshotTest {

    companion object {
        // Microsoft Store's desktop listing screenshot resolution.
        private const val StoreScreenshotWidth = 1366
        private const val StoreScreenshotHeight = 768

        // Every UI language a listing screenshot must be generated in.
        private val StoreScreenshotLanguages = listOf("en", "fr")
    }

    private val outputDir = File("build/store-screenshots").apply { mkdirs() }

    /**
     * The only entry point new screenshot @Test methods should call - see the class doc's RULE.
     * Renders [content] once per [StoreScreenshotLanguages] entry, at
     * [StoreScreenshotWidth]x[StoreScreenshotHeight], wrapped in `LocalAppLocale provides
     * languageTag` (not just relying on a screen's own language param, if it has one) so
     * stringResource actually resolves the requested language rather than whatever
     * Locale.getDefault() happens to be on this machine - see the locale caveat from the "generate
     * Store screenshots" conversation. Writes "build/store-screenshots/{name}_{languageTag}.png".
     */
    private fun captureScreenshot(
        name: String,
        // Real (Thread.sleep, not delay()) extra wait before capturing, for screens whose content
        // finishes loading via a background Dispatchers.IO decode (e.g. GalleryScreen's
        // ThumbnailCache) rather than through Compose's own recomposition/animation machinery -
        // waitForIdle() alone doesn't wait for those, since it only tracks the composition's own
        // pending work. Thread.sleep is used instead of delay() because runDesktopComposeUiTest's
        // v2 API runs this block against a TestDispatcher, which fast-forwards virtual-time
        // delay() calls instead of actually waiting on the wall clock - only a real blocking sleep
        // gives the unrelated IO-dispatcher decode threads real time to finish.
        settleMillis: Long = 0,
        content: @Composable (languageTag: String) -> Unit,
    ) {
        StoreScreenshotLanguages.forEach { languageTag ->
            runDesktopComposeUiTest(StoreScreenshotWidth, StoreScreenshotHeight) {
                setContent {
                    AppTheme {
                        CompositionLocalProvider(LocalAppLocale provides languageTag) {
                            content(languageTag)
                        }
                    }
                }
                waitForIdle()
                if (settleMillis > 0) {
                    Thread.sleep(settleMillis)
                    waitForIdle()
                }
                val file = File(outputDir, "${name}_$languageTag.png")
                ImageIO.write(captureToImage().toAwtImage(), "png", file)
                println("Wrote $file")
            }
        }
    }

    @Test
    fun welcomeScreen() {
        val dummyWindow = Frame()
        captureScreenshot("welcome") { languageTag ->
            WelcomeScreen(
                window = dummyWindow,
                language = languageTag,
                onLanguageChosen = {},
                useNewOpenCv5 = false,
                onUseNewOpenCv5Chosen = {},
                audioOutputDeviceId = "",
                onAudioOutputDeviceChosen = {},
                onFilesChosen = {},
                onImportPlaylist = { true },
                onOpenPlaylistList = {},
                onOpenGallery = {},
                onOpenAbout = {},
                halveLeftRightImages = true,
                onHalveLeftRightImagesChosen = {},
                shrinkControls = false,
                onShrinkControlsChosen = {},
                // Forced on: the test doesn't run inside the MSIX package, but Store users do.
                initialShowDesktopShortcutButton = true,
            )
        }
    }

    @Test
    fun galleryScreen() {
        val sampleImagesDir = File("sample_images")
        check(sampleImagesDir.listFiles()?.isNotEmpty() == true) {
            "No files in $sampleImagesDir - drop some sample JPEGs there before running this test (see .gitignore, they're not committed)."
        }
        // GalleryState.open does the same recursive scan/grouping the real "Open 3D image
        // directory" flow does (see WelcomeScreen/AppViewModel.openGallery), so this renders with
        // real GalleryGroups instead of hand-built ones.
        val galleryState = GalleryState().apply { open(sampleImagesDir) }
        captureScreenshot("gallery", settleMillis = 5000) {
            GalleryScreen(
                groups = galleryState.groups,
                expandedGroups = galleryState.expandedGroups,
                onToggleGroup = {},
                onOpenImage = { _, _ -> },
                onBack = {},
            )
        }
    }

    @Test
    fun imageScreens() {
        // Uses the first photo of samplePlaylist() (a real sample JPEG, not a placeholder bitmap)
        // decoded through ImageScreen's normal disk-decode path - settleMillis gives that async
        // decode (see ImageDecodeCache) time to finish before capture. showInfoPanel/shrinkControls/
        // initialMenuExpanded are plain state passed straight into ImageScreen, matching how
        // AppViewModel would drive them in the real app, rather than simulating menu clicks.
        val firstPhotoFile = File(URI(samplePlaylist().photos.first().imageUriString))
        val secondPhotoFile = File(URI(samplePlaylist().photos[1].imageUriString))
        captureScreenshot("image_view_menu", settleMillis = 2000) {
            ImageScreen(
                file = firstPhotoFile,
                showInfoPanel = true,
                shrinkControls = true,
                initialMenuExpanded = false,
            )
        }
        captureScreenshot("image_view_info_panel", settleMillis = 2000) {
            ImageScreen(
                file = secondPhotoFile,
                showInfoPanel = false,
                halveLeftRightImages = false,
                shrinkControls = false,
                initialMenuExpanded = true,
            )
        }
    }

    /**
     * Builds a [Playlist] from the first two JPEGs in `sample_images`, for tests that need a
     * playlist with real photo entries (not hand-built placeholder paths).
     */
    private fun samplePlaylist(name: String = "SBS 3D Viewer", subtitle: String = "playlist demo"): Playlist {
        val sampleImagesDir = File("sample_images")
        val sampleFiles = sampleImagesDir.listFiles()
            ?.filter { it.extension.lowercase() in listOf("jpg", "jpeg") }
            ?.sorted()
            .orEmpty()
        check(sampleFiles.size >= 2) {
            "Need at least 2 JPEGs in $sampleImagesDir - drop some sample JPEGs there before running this test (see .gitignore, they're not committed)."
        }
        return Playlist(
            name = name,
            absolutePath = sampleImagesDir.absolutePath,
            subtitle = subtitle,
            isAutomated = true,
            photos = listOf(
                PlaylistItem(sampleFiles[0].name, sampleFiles[0].toPath().toUri().toString(), comment = "Palau de la musica catalana"),
                PlaylistItem(sampleFiles[1].name, sampleFiles[1].toPath().toUri().toString(), comment = "Sagrada Familia"),
            ),
        )
    }

    @Test
    fun playlistEditScreen() {
        captureScreenshot("playlist_edit", settleMillis = 5000) {
            PlaylistScreen(
                playlist = samplePlaylist(),
                onAddPhotos = {},
                onAddMusic = {},
                onRemoveSoundtrackItem = {},
                onPlay = { true },
                onBack = {},
                onEditName = { true },
                canRenamePlaylist = { true },
                onModifyDefaultDuration = { true },
                onModifyIsAutomated = { true },
                onModifyScrollSpeedLimitPercent = { true },
                onModifySubtitle = { true },
                onModifyTitleZPercent = { true },
                onModifySubtitleZPercent = { true },
                onModifyTitleStyle = { true },
                onModifySubtitleStyle = { true },
                onModifyCredits = { true },
                onModifyCreditsZPercent = { true },
                onModifyCreditsStyle = { true },
                onModifyCreditsPlacement = { true },
                onReorderPhotos = {},
                onDelete = { true },
                onOpenPlaylistItem = {},
            )
        }
    }
}
