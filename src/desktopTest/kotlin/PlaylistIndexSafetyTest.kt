import fr.camera3d.camera.feature_playlists.domain.PLAYLIST_INDEX_FILENAME
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.service.yamlImporter.getYamlPlaylistFromYaml
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
import kotlin.test.assertTrue

/**
 * A playlist's index is the only copy of its metadata: these cover every path that used to let a
 * load failure turn into a silent wipe of that index on the next save.
 */
class PlaylistIndexSafetyTest {
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

    private fun unreadableCopies(folder: File) =
        folder.listFiles { f -> f.name.startsWith(PLAYLIST_INDEX_UNREADABLE_PREFIX) }.orEmpty().toList()

    private val richIndex = """
        formatVersion : 1.0
        name : "Trip 2026"
        defaultDuration : 7
        subtitle : "keep me"
        photos :
        - filename : "a.jpg"
          comment : "precious comment"
    """.trimIndent() + "\n"

    @Test
    fun `an index with a field this version doesn't know still loads`() {
        val parsed = getYamlPlaylistFromYaml(richIndex + "someFieldFromANewerVersion : 42\n")
        assertEquals("Trip 2026", parsed.name)
        assertEquals("precious comment", parsed.photos.single().comment)
    }

    @Test
    fun `quotes and backslashes in user text survive a save and reload`() {
        val folder = File(root, "Quotes").apply { mkdirs() }
        realJpeg(folder, "a.jpg")
        val storage = DesktopPlaylistStorage(root)
        val tricky = """He said "wow" \ then left"""
        val playlist = Playlist(
            name = tricky, absolutePath = folder.absolutePath, subtitle = tricky, credits = "line 1\nline \"2\"",
            photos = listOf(PlaylistItem("a.jpg", "", comment = tricky)),
        )
        playlist.save(storage)

        val reloaded = Playlist.loadPlaylist(storage, "Quotes")
        assertFalse(reloaded.indexUnreadable)
        assertEquals(tricky, reloaded.name)
        assertEquals(tricky, reloaded.subtitle)
        assertEquals("line 1\nline \"2\"", reloaded.credits)
        assertEquals(tricky, reloaded.photos.single().comment)
    }

    @Test
    fun `saving over an index that doesn't parse keeps the original aside`() {
        val folder = File(root, "Broken").apply { mkdirs() }
        val damaged = "formatVersion : 1.0\nname : \"half-writ"
        File(folder, PLAYLIST_INDEX_FILENAME).writeText(damaged)

        Playlist(name = "Broken", absolutePath = folder.absolutePath).save(DesktopPlaylistStorage(root))

        assertEquals(damaged, unreadableCopies(folder).single().readText())
        assertEquals("Broken", getYamlPlaylistFromYaml(File(folder, PLAYLIST_INDEX_FILENAME).readText()).name)
        assertFalse(File(folder, "$PLAYLIST_INDEX_FILENAME.tmp").exists())
    }

    @Test
    fun `a damaged index is read back from the last good backup`() {
        val folder = File(root, "Backup").apply { mkdirs() }
        realJpeg(folder, "a.jpg")
        val storage = DesktopPlaylistStorage(root)
        File(folder, PLAYLIST_INDEX_FILENAME).writeText(richIndex)
        // Second save: the rich index above becomes the .bak
        Playlist.loadPlaylist(storage, "Backup").save(storage)
        File(folder, PLAYLIST_INDEX_FILENAME).writeText("formatVersion : 1.0\nname : \"trunc")

        val reloaded = Playlist.loadPlaylist(storage, "Backup")

        assertFalse(reloaded.indexUnreadable)
        assertEquals("keep me", reloaded.subtitle)
        assertEquals("precious comment", reloaded.photos.single().comment)
    }

    @Test
    fun `editing a playlist whose index couldn't be read preserves that index and saves in the playlist folder`() {
        val folder = File(root, "Unreadable").apply { mkdirs() }
        realJpeg(folder, "a.jpg")
        // Not valid YAML, and no backup: loadPlaylist can only fall back to a disk scan.
        val damaged = "formatVersion : 1.0\nname : [\n"
        File(folder, PLAYLIST_INDEX_FILENAME).writeText(damaged)
        val editor = PlaylistEditorState(rootOverride = root)
        editor.openList()
        val fallback = editor.all.single()
        assertTrue(fallback.indexUnreadable)
        assertEquals(folder.absolutePath, fallback.absolutePath)

        editor.openForEdit(fallback)
        assertTrue(editor.modifySubtitle("new subtitle"))

        assertEquals(damaged, unreadableCopies(folder).single().readText())
        assertEquals("new subtitle", getYamlPlaylistFromYaml(File(folder, PLAYLIST_INDEX_FILENAME).readText()).subtitle)
        assertFalse(assertNotNull(editor.editing).indexUnreadable)

        // Later saves of the now-valid index must not pile up more "unreadable" copies.
        assertTrue(editor.modifySubtitle("again"))
        assertEquals(1, unreadableCopies(folder).size)
    }

    @Test
    fun `adding photos to an imported folder with no index copies them into that folder`() = runBlocking<Unit> {
        val external = File(sources, "Plain").apply { mkdirs() }
        realJpeg(external, "1.jpg")
        val editor = PlaylistEditorState(rootOverride = root)
        assertNotNull(editor.importFolder(external))

        editor.addPhotos(listOf(realJpeg(sources, "2.jpg")))

        assertTrue(File(root, "Plain/2.jpg").exists())
        assertTrue(File(root, "Plain/$PLAYLIST_INDEX_FILENAME").exists())
        assertEquals(listOf("1.jpg", "2.jpg"), editor.editing?.photos?.map { it.filename })
    }
}
