import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Covers Exif3d's in-place writes (via Exif.modifyExifList's temp-file + atomic replace) and Exif3dFlagsCache. */
class Exif3dWriteTest {
    private val dir = createTempDirectory("exif3d-test").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // Backdated so the rewritten file's lastModified is guaranteed to differ even when the write
    // lands within the same millisecond - Exif3dFlagsCache relies on that change to invalidate.
    private fun realJpeg(name: String): File {
        val file = File(dir, name)
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "jpg", file)
        file.setLastModified(System.currentTimeMillis() - 60_000)
        return file
    }

    @Test
    fun `favorite and warning writes round-trip and leave no temp file behind`() {
        val file = realJpeg("photo.jpg")

        assertTrue(Exif3d.setFavoriteInExif(file, true))
        assertTrue(Exif3d.setWarningInExif(file, true, "ghosting"))

        assertTrue(Exif3d.getFavoriteFromExif(file))
        assertTrue(Exif3d.getWarningFromExif(file))
        assertEquals("ghosting", Exif3d.getWarningCommentFromExif(file))
        assertEquals(listOf("photo.jpg"), dir.list()?.toList(), "the exif_temp_ file must be gone after the swap")
        assertTrue(ImageIO.read(file) != null, "the photo must still decode after the in-place EXIF rewrite")
    }

    @Test
    fun `a failed write leaves the original file untouched`() {
        val notAJpeg = File(dir, "broken.jpg").apply { writeText("not a jpeg") }

        assertFalse(Exif.modifyExif(notAJpeg, "ImageDescription", "{}"))

        assertEquals("not a jpeg", notAJpeg.readText())
        assertEquals(listOf("broken.jpg"), dir.list()?.toList())
    }

    @Test
    fun `Exif3d never throws on a corrupt jpeg - getters read as absent, setters refuse to write`() {
        val corrupt = File(dir, "corrupt.jpg").apply { writeText("truncated garbage") }

        assertFalse(Exif3d.getFavoriteFromExif(corrupt))
        assertEquals(null, Exif3d.get3dCameraCharacteristics(corrupt))
        assertEquals("", Exif3d.getWarningCommentFromExif(corrupt))
        assertFalse(Exif3d.setFavoriteInExif(corrupt, true))
        assertFalse(Exif3d.setWarningInExif(corrupt, true, "x"))
        assertFalse(Exif3d.setTextLegendInExif(corrupt, "legend"))

        assertEquals("truncated garbage", corrupt.readText())
        assertEquals(listOf("corrupt.jpg"), dir.list()?.toList())
    }

    @Test
    fun `flags cache picks up an EXIF write without explicit invalidation`() {
        val file = realJpeg("cached.jpg")
        assertEquals(Exif3dFlagsCache.Flags(favorite = false, warning = false), Exif3dFlagsCache.get(file))

        Exif3d.setFavoriteInExif(file, true)

        assertTrue(Exif3dFlagsCache.get(file).favorite)
    }
}
