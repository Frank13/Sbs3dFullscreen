import androidx.compose.ui.graphics.toComposeImageBitmap
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers AppViewModel's navigation state machine - showNextImage/showPreviousImage/
 * advanceSlideshow, the keepBestOfEachOnly/favoritesOnly filters, the TITLE/PHOTO/END playlist
 * cycle, and the pendingNavigation "unsaved align preview" guard. This is the trickiest logic in
 * the app and, since AppViewModel is a plain (non-Composable) state holder with no AWT/Window
 * references, it's cheap to exercise directly without any UI.
 */
class AppViewModelNavigationTest {

    // AppViewModel persists the photo filters in the real user preferences (and loads them in its
    // constructor), so without this the tests would both depend on, and overwrite, whatever the
    // developer has saved for the real app.
    private val prefs = Preferences.userNodeForPackage(AppViewModel::class.java)
    private val filterKeys = listOf("keepBestOfEachOnly", "favoritesOnly", "excludeStereoIssues")
    private var savedFilters: Map<String, String?> = emptyMap()

    @BeforeTest
    fun isolateFilterPreferences() {
        savedFilters = filterKeys.associateWith { prefs.get(it, null) }
        filterKeys.forEach { prefs.putBoolean(it, false) }
    }

    @AfterTest
    fun restoreFilterPreferences() {
        savedFilters.forEach { (key, value) -> if (value == null) prefs.remove(key) else prefs.put(key, value) }
        prefs.flush()
    }

    private fun realJpeg(dir: File, name: String): File {
        val file = File(dir, name)
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", file)
        return file
    }

    @Test
    fun `showNextImage and showPreviousImage clamp at the ends of a plain file selection`() {
        val dir = createTempDirectory("nav-test").toFile()
        val files = listOf(File(dir, "a.jpg"), File(dir, "b.jpg"), File(dir, "c.jpg"))
        val vm = AppViewModel(null)
        vm.onFilesChosen(files)

        assertEquals(0, vm.currentImageIndex)
        vm.showPreviousImage()
        assertEquals(0, vm.currentImageIndex) // no wraparound backward

        vm.showNextImage()
        vm.showNextImage()
        assertEquals(2, vm.currentImageIndex)
        vm.showNextImage()
        assertEquals(2, vm.currentImageIndex) // clamped at the last photo
    }

    @Test
    fun `keepBestOfEachOnly skips a raw photo once its edited version exists`() {
        val dir = createTempDirectory("nav-test").toFile()
        val files = listOf(
            File(dir, "photo_raw.sbs.jpg"),
            File(dir, "photo_edited.sbs.jpg"),
            File(dir, "other.jpg"),
        )
        val vm = AppViewModel(null)
        vm.onFilesChosen(files)
        vm.onKeepBestOfEachOnlyChosen(true)

        // Turning the filter on while sitting on the raw version (not the best of its group)
        // should have snapped forward to the edited one already.
        assertEquals(files[1], vm.imageFiles[vm.currentImageIndex])

        vm.showNextImage()
        assertEquals(files[2], vm.imageFiles[vm.currentImageIndex])
    }

    @Test
    fun `favoritesOnly skips photos without the EXIF3D favorite flag`() {
        val dir = createTempDirectory("nav-test").toFile()
        val plain = realJpeg(dir, "plain.jpg")
        val favorite = realJpeg(dir, "favorite.jpg")
        Exif3d.setFavoriteInExif(favorite, true)
        val files = listOf(plain, favorite)

        val vm = AppViewModel(null)
        vm.onFilesChosen(files)
        vm.onFavoritesOnlyChosen(true)

        assertEquals(favorite, vm.imageFiles[vm.currentImageIndex])
        vm.showNextImage()
        assertEquals(favorite, vm.imageFiles[vm.currentImageIndex]) // nothing further to skip to
    }

    @Test
    fun `excludeStereoIssues skips photos flagged in EXIF3D or spotted by file name`() {
        val dir = createTempDirectory("nav-test").toFile()
        val flagged = realJpeg(dir, "flagged.jpg")
        Exif3d.setWarningInExif(flagged, true, "ghosting")
        val spotted = realJpeg(dir, "photo_stereo_issues.sbs.jpg")
        val clean = realJpeg(dir, "clean.jpg")
        val files = listOf(flagged, spotted, clean)

        val vm = AppViewModel(null)
        vm.onFilesChosen(files)
        vm.onExcludeStereoIssuesChosen(true)

        assertEquals(clean, vm.currentImage) // snapped forward past both flagged photos
        vm.showPreviousImage()
        assertEquals(clean, vm.currentImage) // nothing visible before it
    }

    @Test
    fun `edit tools and delete are refused while a playlist plays`() = runBlocking<Unit> {
        val dir = createTempDirectory("nav-test").toFile()
        val photoFiles = listOf(realJpeg(dir, "1.jpg"), realJpeg(dir, "2.jpg"))
        val items = photoFiles.map { PlaylistItem(it.name, it.toURI().toString()) }
        val playlist = Playlist(name = "Test", absolutePath = dir.absolutePath, photos = items)

        val vm = AppViewModel(null)
        vm.onPlaylistChosen(playlist, photoFiles, isAutomated = false, intervalMs = 1000)
        vm.showNextImage() // TITLE -> first photo

        vm.startCrop()
        vm.startManualAlign()
        vm.startSpotIssues()
        vm.startClickAlign()
        assertFalse(vm.photoTools.cropMode || vm.photoTools.manualAlignMode || vm.photoTools.spotIssuesMode || vm.photoTools.clickAlignMode)

        vm.performDeleteCurrentImage()
        assertTrue(photoFiles[0].exists(), "a playlist photo must not be deleted from the slideshow")
        assertEquals(photoFiles, vm.imageFiles)
    }

    @Test
    fun `playlist navigation cycles TITLE then photos then END then back to TITLE`() {
        val dir = createTempDirectory("nav-test").toFile()
        val photoFiles = listOf(realJpeg(dir, "1.jpg"), realJpeg(dir, "2.jpg"))
        val items = photoFiles.map { PlaylistItem(it.name, it.toURI().toString()) }
        val playlist = Playlist(name = "Test", absolutePath = dir.absolutePath, photos = items)

        val vm = AppViewModel(null)
        vm.onPlaylistChosen(playlist, photoFiles, isAutomated = false, intervalMs = 1000)

        assertEquals(PlaylistSlideKind.TITLE, vm.playlistSlideKind)

        vm.showNextImage()
        assertEquals(PlaylistSlideKind.PHOTO, vm.playlistSlideKind)
        assertEquals(photoFiles[0], vm.currentImage)

        vm.showNextImage()
        assertEquals(photoFiles[1], vm.currentImage)

        vm.showNextImage()
        assertEquals(PlaylistSlideKind.END, vm.playlistSlideKind)

        vm.showNextImage()
        assertEquals(PlaylistSlideKind.TITLE, vm.playlistSlideKind) // wraps back around

        vm.showPreviousImage()
        assertEquals(PlaylistSlideKind.TITLE, vm.playlistSlideKind) // no wraparound backward past TITLE
    }

    @Test
    fun `photo filters never apply while a playlist plays`() {
        val dir = createTempDirectory("nav-test").toFile()
        val photoFiles = listOf(realJpeg(dir, "1.jpg"), realJpeg(dir, "2.jpg"), realJpeg(dir, "3.jpg"))
        val items = photoFiles.map { PlaylistItem(it.name, it.toURI().toString()) }
        val playlist = Playlist(name = "Test", absolutePath = dir.absolutePath, photos = items)

        val vm = AppViewModel(null)
        vm.onFavoritesOnlyChosen(true) // none of the photos is a favorite
        vm.onKeepBestOfEachOnlyChosen(true)
        vm.onExcludeStereoIssuesChosen(true)
        vm.onPlaylistChosen(playlist, photoFiles, isAutomated = false, intervalMs = 1000)

        vm.showNextImage() // TITLE -> first photo
        photoFiles.forEach { photo ->
            assertEquals(photo, vm.currentImage)
            vm.showNextImage()
        }
        assertEquals(PlaylistSlideKind.END, vm.playlistSlideKind)
    }

    @Test
    fun `showNextImage defers to a pending unsaved align preview instead of navigating immediately`() {
        val dir = createTempDirectory("nav-test").toFile()
        val files = listOf(File(dir, "a.jpg"), File(dir, "b.jpg"))
        val vm = AppViewModel(null)
        vm.onFilesChosen(files)

        val previewBitmap = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).toComposeImageBitmap()
        vm.applyAlignedPreview(AutoAlign.AutoAlignResult(previewBitmap, null, null), AlignKind.HOMOGRAPHY)

        vm.showNextImage()
        assertEquals(0, vm.currentImageIndex) // blocked - still on the first photo
        assertEquals(PendingNavigationDirection.NEXT, vm.pendingNavigation)

        vm.discardAlignedPreviewAndNavigate()
        assertEquals(1, vm.currentImageIndex)
        assertNull(vm.pendingNavigation)
        assertNull(vm.photoTools.alignedPreview)
    }
}
