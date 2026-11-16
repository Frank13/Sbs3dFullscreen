import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Detects cloud-storage placeholders (OneDrive "Files On-Demand" online-only files, or any other
 * Windows Cloud Files API provider) whose content isn't on this disk yet.
 *
 * Reading such a file is normally transparent: Windows blocks the read while the provider
 * downloads it. It only fails when the provider can't serve it - OneDrive not running/signed in
 * ("The cloud file provider is not running"), no network, or a download timeout - which are all
 * transient, unlike a corrupt/0-byte file. This is what lets callers tell the two apart (the
 * exception message alone is localized and not reliable) and retry instead of giving up.
 */
object CloudFiles {
    // Raw Win32 attribute bits (winnt.h). None of them is exposed by Java's DosFileAttributes.
    private const val FileAttributeOffline = 0x1000
    private const val FileAttributeRecallOnOpen = 0x40000
    private const val FileAttributeRecallOnDataAccess = 0x400000
    private const val NotLocalMask = FileAttributeOffline or FileAttributeRecallOnOpen or FileAttributeRecallOnDataAccess

    /**
     * True if [file]'s content still has to be downloaded from its cloud provider. Reading the
     * attributes never triggers that download. Never throws: false when the attributes can't be
     * read (missing file, non-Windows file system, ...).
     */
    fun isNotDownloaded(file: File): Boolean = try {
        // "dos:attributes" (undocumented but stable since Java 7) returns the raw Win32 attribute int.
        val attributes = Files.getAttribute(file.toPath(), "dos:attributes", LinkOption.NOFOLLOW_LINKS) as Int
        attributes and NotLocalMask != 0
    } catch (_: Exception) {
        false
    }
}
