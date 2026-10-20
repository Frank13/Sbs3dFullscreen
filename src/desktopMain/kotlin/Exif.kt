import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffDirectoryConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.fieldtypes.AbstractFieldType
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfo
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoAscii
import org.apache.commons.imaging.formats.tiff.taginfos.TagInfoGpsText
import org.apache.commons.imaging.formats.tiff.write.TiffOutputField
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-based EXIF read/write, desktop counterpart to CameraSync3D's Context/Uri-based Exif.kt.
 * Uses Apache Commons Imaging (pure JVM) instead of Android's UnicodeExifInterface.
 *
 * Only a curated set of tags is wired up (whatever Exif3d and the desktop UI actually need) rather
 * than the full ~140-tag list Android's Exif.kt supports - extend [tagsByName] as more are needed.
 */
object Exif {
    private val tagsByName: Map<String, TagInfo> = mapOf(
        "ImageDescription" to TiffTagConstants.TIFF_TAG_IMAGE_DESCRIPTION,
        // EXIF_TAG_USER_COMMENT is a TagInfoGpsText: same "8-byte character-code prefix" encoding
        // as GPSProcessingMethod, which is exactly the ASCII/Unicode ambiguity CameraSync3D's
        // UnicodeExifInterface fork exists to work around on Android. It's handled with our own
        // decodeUserComment/encodeUserComment below rather than Commons Imaging's built-in
        // TagInfoGpsText codec - see the comment above those functions for why.
        "UserComment" to ExifTagConstants.EXIF_TAG_USER_COMMENT,
        "Copyright" to TiffTagConstants.TIFF_TAG_COPYRIGHT,
        "Software" to TiffTagConstants.TIFF_TAG_SOFTWARE,
        // Commons Imaging names this tag "FocalLengthIn35mmFormat"; it's the same 0xa405 EXIF tag
        // Android's Exif.kt reads under the EXIF spec's own name "FocalLengthIn35mmFilm".
        "FocalLengthIn35mmFilm" to ExifTagConstants.EXIF_TAG_FOCAL_LENGTH_IN_35MM_FORMAT,
    )

    /**
     * Directory (root IFD0 vs Exif sub-IFD) each tag belongs to - needed when writing, since
     * Commons Imaging's TiffOutputSet keeps them as separate directories.
     */
    private val exifIfdTags: Set<String> = setOf("UserComment")

    // EXIF's 8-byte character-code prefix for UserComment/GPSProcessingMethod-style "undefined"
    // text fields, per the EXIF spec: "ASCII\0\0\0" and "UNICODE\0".
    private val UserCommentAsciiPrefix = byteArrayOf(0x41, 0x53, 0x43, 0x49, 0x49, 0, 0, 0)
    private val UserCommentUnicodePrefix = byteArrayOf(0x55, 0x4E, 0x49, 0x43, 0x4F, 0x44, 0x45, 0)

    /**
     * UserComment is read/written with our own raw-byte encoding instead of going through Commons
     * Imaging's [TagInfoGpsText], which picks UTF-16 endianness from the file's declared TIFF byte
     * order. The Android sibling app (CameraSync3D) writes UserComment via the third-party
     * `com.github.ddyos:UnicodeExifInterface` library, which - for JPEG - always encodes the
     * "UNICODE\0"-prefixed text as UTF-16 *big-endian*, regardless of the file's actual declared
     * byte order. When that byte order is little-endian (common for Android-written JPEGs),
     * Commons Imaging correctly-per-spec decodes those bytes as UTF-16LE, which byte-swaps every
     * code unit and turns plain Latin text into CJK/Hangul-looking mojibake (e.g. "e" as UTF-16BE
     * 0x00 0x65, read back as UTF-16LE, becomes U+6500, a CJK ideograph) - this is the "legend
     * shown in Japanese-like characters" bug. Hardcoding UTF-16BE here on both the read and write
     * side keeps us consistent with the Android app regardless of a given file's byte order.
     */
    private fun decodeUserComment(bytes: ByteArray): String {
        val prefixLength = UserCommentUnicodePrefix.size
        return when {
            bytes.size >= prefixLength && bytes.copyOfRange(0, prefixLength).contentEquals(UserCommentUnicodePrefix) ->
                String(bytes, prefixLength, bytes.size - prefixLength, Charsets.UTF_16BE)
            bytes.size >= UserCommentAsciiPrefix.size && bytes.copyOfRange(0, UserCommentAsciiPrefix.size).contentEquals(UserCommentAsciiPrefix) ->
                String(bytes, UserCommentAsciiPrefix.size, bytes.size - UserCommentAsciiPrefix.size, Charsets.US_ASCII)
            else -> String(bytes, Charsets.US_ASCII)
        }.trimEnd(' ')
    }

    private fun encodeUserComment(value: String): ByteArray {
        val isAscii = value.all { it.code in 0..127 }
        return if (isAscii) UserCommentAsciiPrefix + value.toByteArray(Charsets.US_ASCII)
        else UserCommentUnicodePrefix + value.toByteArray(Charsets.UTF_16BE)
    }

    // Commons Imaging throws IllegalArgumentException("Unknown extension") for files it has no
    // parser for (e.g. a video reaching the favorites/stereo-issues filters), so only the JPEG
    // extensions it recognises are handed to it; anything else simply has no EXIF.
    private val jpegExtensions = setOf("jpg", "jpeg")

    private fun jpegMetadataOf(file: File): JpegImageMetadata? =
        if (file.extension.lowercase() in jpegExtensions) Imaging.getMetadata(file) as? JpegImageMetadata else null

    fun getExifTag(file: File, tag: String): String? {
        val tagInfo = tagsByName[tag] ?: return null
        val metadata = jpegMetadataOf(file) ?: return null
        val field = metadata.findExifValueWithExactMatch(tagInfo) ?: return null
        // getStringValue() only works for ASCII-typed tags; FocalLengthIn35mmFilm is a numeric
        // SHORT field (getStringValue() throws "Expected String value" for it), same reason
        // UserComment needs its own byte-array-based decoding below.
        return when (tag) {
            "UserComment" -> decodeUserComment(field.byteArrayValue).trim(' ')
            "FocalLengthIn35mmFilm" -> field.intValue.toString()
            else -> field.stringValue?.trim(' ')
        }
    }

    fun getExifTags(file: File, tags: Array<String>): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        for (tag in tags) {
            result[tag] = getExifTag(file, tag) ?: "-"
        }
        return result
    }

    /**
     * modifies multiple tags in the EXIF for the file, atomically: writes to a temp file first,
     * verifies it, then replaces the original - mirrors Android Exif.kt's modifyExifList so a
     * process kill mid-write can't corrupt the original photo.
     */
    fun modifyExifList(file: File, tagValuePairs: List<Pair<String, String>>, onError: ((String) -> Unit)? = null): Boolean {
        val tempFile = File.createTempFile("exif_temp_", ".jpg", file.parentFile)
        try {
            val existingMetadata = jpegMetadataOf(file)
            val outputSet: TiffOutputSet = existingMetadata?.exif?.outputSet ?: TiffOutputSet()

            for ((tag, value) in tagValuePairs) {
                val tagInfo = tagsByName[tag] ?: continue
                val directory = if (tag in exifIfdTags) outputSet.getOrCreateExifDirectory() else outputSet.getOrCreateRootDirectory()
                directory.removeField(tagInfo)
                if (tag == "UserComment") {
                    val bytes = encodeUserComment(value)
                    directory.add(TiffOutputField(tagInfo, AbstractFieldType.UNDEFINED, bytes.size, bytes))
                } else {
                    when (tagInfo) {
                        is TagInfoAscii -> directory.add(tagInfo, value)
                        is TagInfoGpsText -> directory.add(tagInfo, value)
                        else -> throw Exception("Unsupported tag type for '$tag': ${tagInfo.javaClass.simpleName}")
                    }
                }
            }

            FileOutputStream(tempFile).use { out ->
                ExifRewriter().updateExifMetadataLossless(file, out, outputSet)
            }

            val (firstTag, expectedValue) = tagValuePairs.first()
            if (getExifTag(tempFile, firstTag) != expectedValue) {
                throw Exception("EXIF verify failed: $firstTag expected '$expectedValue'")
            }

            replaceAtomically(tempFile, file)
            return true
        } catch (e: Exception) {
            onError?.invoke(e.message ?: e.toString())
            return false
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Swaps [tempFile] in for [target] in a single rename rather than copyTo(overwrite = true),
     * which truncates [target] first and rewrites it byte by byte - a kill, full disk or I/O error
     * mid-copy would then leave the user's original photo truncated. Both files live in the same
     * directory (see the createTempFile calls), so the rename stays on one volume and NTFS can do
     * it atomically; the plain-move fallback only covers file systems that refuse ATOMIC_MOVE.
     */
    private fun replaceAtomically(tempFile: File, target: File) {
        try {
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun modifyExif(file: File, tag: String, value: String): Boolean =
        modifyExifList(file, listOf(tag to value))

    fun getExifUserComment(file: File, maxChars: Int = -1): String? {
        val comment = getExifTag(file, "UserComment")
        return if (maxChars < 0 || comment == null) comment else comment.take(maxChars)
    }

    fun getExifCopyright(file: File): String = getExifTag(file, "Copyright") ?: ""

    fun getExifFocalLengthIn35mmFilm(file: File): Int? = getExifTag(file, "FocalLengthIn35mmFilm")?.toIntOrNull()

    fun setExifCopyright(file: File, copyright: String): Boolean = modifyExif(file, "Copyright", copyright)

    fun setExifUserComment(file: File, userComment: String): Boolean = modifyExif(file, "UserComment", userComment)

    fun setExifSoftware(file: File): Boolean = modifyExif(file, "Software", "sbs3Dfullscreen")

    /**
     * Copies [source]'s *entire* EXIF tree onto [dest] - Make/Model/DateTimeOriginal/GPS/aperture/
     * ISO/lens etc., not just this app's own curated [tagsByName] subset - e.g. after writing a
     * freshly re-encoded (EXIF-less) aligned/cropped copy of a photo to a new file. Moves Commons
     * Imaging's already-parsed [TiffOutputSet] across directly rather than going through
     * [modifyExifList]'s per-tag decode/encode loop, since that machinery only covers the handful of
     * tags this app itself reads/writes, not the full tag set a camera-written JPEG carries. This
     * also carries across [tagsByName]'s own tags (ImageDescription's packed Exif3d data included),
     * since those are already part of [source]'s parsed tree.
     *
     * Both the parse of [source]'s existing EXIF and the write onto [dest] are wrapped and logged
     * rather than left to throw - a malformed/atypical source EXIF tree (e.g. re-saved by another
     * tool) can make either step fail, and since [dest]'s pixels are already written by the time
     * this runs (see [writeAlignedResult]), a caller must be able to tell "saved, but metadata
     * copy failed" apart from an actual crash. Returns null on success, or the failure's message
     * (surfaced to the user via [AutoAlign.SavedFile.exifError]/the save toast) otherwise.
     */
    fun copyExif(source: File, dest: File): String? {
        val outputSet = try {
            jpegMetadataOf(source)?.exif?.outputSet ?: return null
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            System.err.println("Exif.copyExif: failed to read EXIF from '${source.path}': $message")
            return message
        }
        stripStaleDimensionData(outputSet)
        val tempFile = File.createTempFile("exif_temp_", ".jpg", dest.parentFile)
        return try {
            FileOutputStream(tempFile).use { out -> ExifRewriter().updateExifMetadataLossless(dest, out, outputSet) }
            replaceAtomically(tempFile, dest)
            null
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            System.err.println("Exif.copyExif: failed to write EXIF onto '${dest.path}': $message")
            message
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Drops metadata from [outputSet] that describes [source]'s pixels rather than [dest]'s: an
     * aligned/cropped save can change dimensions (crop) or content (align/spot-issues), so
     * copying-across [source]'s root-IFD ImageWidth/ImageLength and Exif-SubIFD
     * ExifImageWidth/ExifImageLength (PixelXDimension/PixelYDimension) tags as-is would describe the
     * wrong image, and its embedded IFD1 thumbnail would still show the pre-edit photo. Neither is
     * recalculated to match [dest] instead - viewers use the JPEG's own SOF marker for display size,
     * not these EXIF tags, so there's nothing depending on them being present - dropping them is
     * simpler and avoids re-desyncing them on a future edit.
     */
    private fun stripStaleDimensionData(outputSet: TiffOutputSet) {
        outputSet.removeField(TiffTagConstants.TIFF_TAG_IMAGE_WIDTH)
        outputSet.removeField(TiffTagConstants.TIFF_TAG_IMAGE_LENGTH)
        outputSet.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_WIDTH)
        outputSet.removeField(ExifTagConstants.EXIF_TAG_EXIF_IMAGE_LENGTH)
        outputSet.findDirectory(TiffDirectoryConstants.DIRECTORY_TYPE_THUMBNAIL)?.setJpegImageData(null)
    }
}
