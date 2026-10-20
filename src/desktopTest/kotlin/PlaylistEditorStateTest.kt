import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers PlaylistEditorState's on-disk CRUD against a temp playlists root (its rootOverride), never
 * the user's real Pictures/SBS 3D Viewer folder.
 */
class PlaylistEditorStateTest {
    private val root = createTempDirectory("playlists-root").toFile()
    private val sources = createTempDirectory("playlist-sources").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        sources.deleteRecursively()
    }

    private fun realJpeg(dir: File, name: String): File {
        val file = File(dir, name)
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", file)
        return file
    }

    @Test
    fun `rename moves the folder and re-resolves every photo to it`() = runBlocking<Unit> {
        val editor = PlaylistEditorState(rootOverride = root)
        editor.startCreate("My List")
        editor.addPhotos(listOf(realJpeg(sources, "a.jpg"), realJpeg(sources, "b.jpg")))

        assertTrue(editor.rename("Renamed List"))

        val playlist = assertNotNull(editor.editing)
        val newFolder = File(root, "RenamedList")
        assertEquals(newFolder.absolutePath, playlist.absolutePath)
        assertFalse(File(root, "MyList").exists())
        for (photo in playlist.photos) {
            val file = playlistItemFile(photo.imageUriString)
            assertTrue(file.exists(), "${photo.filename} should resolve to an existing file after the rename")
            assertEquals(newFolder.canonicalFile, file.parentFile.canonicalFile)
        }
    }

    @Test
    fun `adding a photo whose name is already taken gets a numbered copy`() = runBlocking<Unit> {
        val editor = PlaylistEditorState(rootOverride = root)
        editor.startCreate("Dupes")
        val photo = realJpeg(sources, "photo.jpg")
        editor.addPhotos(listOf(photo))
        editor.addPhotos(listOf(photo))

        assertEquals(listOf("photo.jpg", "photo (2).jpg"), editor.editing?.photos?.map { it.filename })
        assertFalse(editor.isCopying)
    }

    @Test
    fun `importing a folder copies it in, and a second import of the same name is refused`() = runBlocking<Unit> {
        val external = File(sources, "Trip").apply { mkdirs() }
        realJpeg(external, "1.jpg")
        val editor = PlaylistEditorState(rootOverride = root)

        val imported = assertNotNull(editor.importFolder(external))
        assertEquals(listOf("1.jpg"), imported.photos.map { it.filename })
        assertTrue(File(root, "Trip/1.jpg").exists())
        assertTrue(File(external, "1.jpg").exists(), "the source folder must be left untouched")

        assertNull(editor.importFolder(external))
        assertFalse(editor.isCopying)
    }

    @Test
    fun `deleteItem removes the photo from the index and from disk`() = runBlocking<Unit> {
        val editor = PlaylistEditorState(rootOverride = root)
        editor.startCreate("Delete")
        editor.addPhotos(listOf(realJpeg(sources, "a.jpg"), realJpeg(sources, "b.jpg")))
        editor.openItem(0)

        assertTrue(editor.deleteItem())

        assertEquals(listOf("b.jpg"), editor.editing?.photos?.map { it.filename })
        assertFalse(File(root, "Delete/a.jpg").exists())
    }
}
