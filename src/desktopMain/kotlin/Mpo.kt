import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Reads Multi Picture Object (MPO) stereo photos - the format several 3D cameras (Fujifilm, Sony,
 * ...) write natively: one file containing two complete, independent JPEG frames (left eye then
 * right eye) concatenated back to back, rather than a single side-by-side bitmap. Neither of this
 * app's existing image libraries understands that container: Apache Commons Imaging (used for
 * EXIF - see Exif.kt) has no MPO/MPF support as of 1.0.0-alpha6, and metadata-extractor doesn't
 * either as of 2.19.0 (still an open feature request, drewnoakes/metadata-extractor#654) - so
 * frame boundaries are found by hand here, walking real JPEG segment markers rather than naively
 * scanning for the next 0xFFD8/0xFFD9 byte pair. Naive scanning would misfire: the first frame's
 * own EXIF almost always embeds a thumbnail, itself a complete JPEG with its own SOI/EOI, nested
 * inside an APP1 segment.
 *
 * Deliberately never writes a converted copy to disk by itself: [decodeComposedImage] composes the
 * two frames purely in memory, for display only (ImageScreen.kt/GalleryScreen.kt's thumbnails) - so
 * merely opening or browsing an .mpo never leaves a copy next to it on disk. A real ".sbs.jpg" file
 * is only ever created as the result of an actual edit ("Save" after crop/align/manual-align/
 * spot-issues), via AutoAlign.kt's own MPO handling in fileToMat (composes the two frames into a
 * Mat, reusing [splitFrames]) and nextAvailableFile (names an MPO-sourced save ".sbs.jpg").
 */
object Mpo {
    fun isMpoFile(file: File): Boolean = file.extension.equals("mpo", ignoreCase = true)

    private const val Eoi = 0xD9
    private const val Sos = 0xDA

    /** JPEG markers with no length-prefixed segment to skip (stand alone, no payload). */
    private fun isStandaloneMarker(marker: Int): Boolean = marker == 0x01 || marker in 0xD0..0xD7

    private fun u8(bytes: ByteArray, i: Int): Int = bytes[i].toInt() and 0xFF

    private fun u16(bytes: ByteArray, i: Int): Int = (u8(bytes, i) shl 8) or u8(bytes, i + 1)

    /**
     * Splits [bytes] into each complete embedded JPEG frame (SOI..EOI, inclusive) it contains, by
     * walking real JPEG segment markers: header segments (APPn/DQT/DHT/SOF/...) are skipped
     * wholesale by their own declared length - so a marker-like byte pair inside, say, an embedded
     * EXIF thumbnail never gets misread as this frame's own boundary - and the compressed scan
     * data after SOS is walked respecting 0xFF 0x00 byte-stuffing and restart markers (0xD0-0xD7),
     * so the first EOI actually found is the real one. Stops (returning whatever frames were
     * already found complete) on any malformed/truncated segment rather than emitting a partial
     * frame.
     */
    fun splitFrames(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i + 1 < bytes.size) {
            if (u8(bytes, i) != 0xFF || u8(bytes, i + 1) != 0xD8) {
                i++
                continue
            }
            val start = i
            var j = i + 2
            var frameEnd = -1
            while (j + 1 < bytes.size) {
                if (u8(bytes, j) != 0xFF) {
                    j++
                    continue
                }
                val marker = u8(bytes, j + 1)
                j += 2
                if (isStandaloneMarker(marker)) continue
                if (marker != Sos) {
                    if (j + 1 >= bytes.size) break
                    j += u16(bytes, j)
                    continue
                }
                // SOS: skip its own header segment, then walk the compressed scan data for the real EOI.
                if (j + 1 >= bytes.size) break
                j += u16(bytes, j)
                while (j + 1 < bytes.size) {
                    if (u8(bytes, j) == 0xFF) {
                        val b2 = u8(bytes, j + 1)
                        when {
                            b2 == 0x00 || b2 in 0xD0..0xD7 -> j += 2 // stuffed byte / restart marker
                            b2 == Eoi -> {
                                j += 2
                                frameEnd = j
                            }
                            else -> j += 2 // unexpected marker mid-scan - keep looking for the real EOI
                        }
                        if (frameEnd != -1) break
                    } else {
                        j++
                    }
                }
                break
            }
            if (frameEnd == -1) break
            frames.add(bytes.copyOfRange(start, frameEnd))
            i = frameEnd
        }
        return frames
    }

    /**
     * Decodes [file]'s first two stereo frames and composes them side by side into a single
     * in-memory bitmap, purely for display (ImageScreen.kt/GalleryScreen.kt's thumbnails) - never
     * written to disk. Returns null if [file] isn't recognized as MPO, has fewer than 2 frames, or
     * a frame fails to decode.
     */
    fun decodeComposedImage(file: File): BufferedImage? {
        if (!isMpoFile(file)) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return decodeComposedImage(bytes)
    }

    /**
     * Same as the [File] overload, for bytes the caller already read - so it can tell a failed
     * read (e.g. an offline OneDrive file, see ImageDecodeCache) from an undecodable file.
     */
    fun decodeComposedImage(bytes: ByteArray): BufferedImage? {
        val frames = runCatching { splitFrames(bytes) }.getOrNull() ?: return null
        if (frames.size < 2) return null
        val left = ImageIO.read(frames[0].inputStream()) ?: return null
        val right = ImageIO.read(frames[1].inputStream()) ?: return null
        val combined = BufferedImage(left.width + right.width, maxOf(left.height, right.height), BufferedImage.TYPE_INT_RGB)
        val g = combined.createGraphics()
        g.drawImage(left, 0, 0, null)
        g.drawImage(right, left.width, 0, null)
        g.dispose()
        return combined
    }
}
