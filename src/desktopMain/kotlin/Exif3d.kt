import fr.camera3d.camera.shared.Desc3d
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-file cache of the two EXIF3D flags AppViewModel's photo filters (favoritesOnly/
 * excludeStereoIssues) test on every Next/Previous - without it, each key press re-parsed the EXIF
 * of every file in the selection on the UI thread (twice with both filters on). Keyed on the
 * file's lastModified as well as its path, so an EXIF write (InfoPanel's favorite/warning toggles,
 * which replace the file) invalidates its entry by itself. Filled ahead of time by Main.kt's
 * [warm] call on a background thread, so the filters normally only ever hit the cache.
 */
object Exif3dFlagsCache {
    data class Flags(val favorite: Boolean, val warning: Boolean)

    private data class Entry(val lastModified: Long, val flags: Flags)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(file: File): Flags {
        val lastModified = file.lastModified()
        cache[file.path]?.takeIf { it.lastModified == lastModified }?.let { return it.flags }
        // One parse for both flags; an unreadable file reads as neither (see Exif3d's error handling).
        val desc = Exif3d.get3dCameraCharacteristics(file)
        val flags = Flags(favorite = desc?.favorite == true, warning = desc?.warning == true)
        cache[file.path] = Entry(lastModified, flags)
        return flags
    }

    /** Reads (or re-validates) every file's flags - call off the UI thread. */
    fun warm(files: List<File>) {
        files.forEach(::get)
    }
}

/**
 * File-based counterpart to CameraSync3D's Exif3d.kt: reads/writes the same EXIF3D JSON scheme
 * (Desc3d, synced verbatim from Android) through the "ImageDescription" EXIF tag.
 *
 * Never throws: Commons Imaging throws on a corrupt/truncated JPEG (which the app can be launched
 * on directly via file association), and these are called from UI coroutines (InfoPanel, the photo
 * filters) where an escaping exception would take down the window. A getter on an unreadable file
 * answers as if the tag were absent; a setter returns false without writing anything.
 */
object Exif3d {
    /** The file's Desc3d, null if it has none - or if its EXIF can't be read at all. */
    fun get3dCameraCharacteristics(file: File): Desc3d? = readDesc(file).getOrNull()

    fun getFavoriteFromExif(file: File): Boolean = get3dCameraCharacteristics(file)?.favorite == true

    fun setFavoriteInExif(file: File, favorite: Boolean): Boolean =
        updateDesc(file) { it.copy(favorite = favorite) }

    fun getWarningFromExif(file: File): Boolean = get3dCameraCharacteristics(file)?.warning == true

    fun setWarningInExif(file: File, warning: Boolean, comment: String? = null): Boolean =
        updateDesc(file) { current ->
            if (warning) current.copy(warning = true, warningComment = comment ?: current.warningComment)
            else current.copy(warning = false, warningComment = "")
        }

    fun getWarningCommentFromExif(file: File): String = get3dCameraCharacteristics(file)?.warningComment ?: ""

    fun getLegendFromExif(file: File): Boolean = get3dCameraCharacteristics(file)?.hasLegend == true

    fun setLegendInExif(file: File, hasLegend: Boolean): Boolean {
        val current = readDescForUpdate(file) ?: return false
        val updated = current.copy(hasLegend = hasLegend)
        return Exif.modifyExifList(file, listOf("UserComment" to "", "ImageDescription" to updated.toString()))
    }

    fun setTextLegendInExif(file: File, legend: String): Boolean {
        val current = readDescForUpdate(file) ?: return false
        val updated = current.copy(hasLegend = false)
        return Exif.modifyExifList(file, listOf("UserComment" to legend, "ImageDescription" to updated.toString()))
    }

    /** Success(null) when the file simply has no Desc3d; Failure when its EXIF can't be read/parsed. */
    private fun readDesc(file: File): Result<Desc3d?> = runCatching {
        Exif.getExifTag(file, "ImageDescription")?.let(Desc3d::parse)
    }

    /**
     * The Desc3d to start an update from: the existing one, a fresh Desc3d() if the file has none,
     * or null if the EXIF can't be read - in which case the caller must not write, since starting
     * over from Desc3d() would silently wipe the photo's other EXIF3D fields.
     */
    private fun readDescForUpdate(file: File): Desc3d? = readDesc(file).getOrElse { return null } ?: Desc3d()

    private fun updateDesc(file: File, transform: (Desc3d) -> Desc3d): Boolean {
        val current = readDescForUpdate(file) ?: return false
        return Exif.modifyExif(file, "ImageDescription", transform(current).toString())
    }
}
