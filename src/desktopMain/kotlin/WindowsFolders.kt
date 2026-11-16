import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * The user's real Windows special folders, asked from the shell rather than assumed to be
 * ~/Pictures, ~/Desktop, ...: OneDrive backup (on by default on many Windows 11 setups) redirects
 * them to ~/OneDrive/Images, ~/OneDrive/Desktop, ... and their on-disk names are localized.
 * Every getter falls back to the conventional ~/<name> folder and never throws.
 */
object WindowsFolders {
    private const val CSIDL_PERSONAL = 0x0005
    private const val CSIDL_MYMUSIC = 0x000d
    private const val CSIDL_MYVIDEO = 0x000e
    private const val CSIDL_DESKTOPDIRECTORY = 0x0010
    private const val CSIDL_MYPICTURES = 0x0027
    private const val CSIDL_PROFILE = 0x0028
    private const val MAX_PATH = 260

    private interface Shell32 : StdCallLibrary {
        fun SHGetFolderPathW(hwnd: Pointer?, csidl: Int, hToken: Pointer?, dwFlags: Int, pszPath: CharArray): Int
    }

    private val shell32: Shell32? by lazy {
        try {
            Native.load("shell32", Shell32::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    private fun folder(csidl: Int, fallbackName: String?): File {
        val resolved = try {
            val buffer = CharArray(MAX_PATH)
            if (shell32?.SHGetFolderPathW(null, csidl, null, 0, buffer) == 0) {
                File(Native.toString(buffer)).takeIf { it.isDirectory }
            } else null
        } catch (_: Throwable) {
            null
        }
        val home = File(System.getProperty("user.home"))
        return resolved ?: if (fallbackName == null) home else File(home, fallbackName)
    }

    val home: File by lazy { folder(CSIDL_PROFILE, null) }
    val desktop: File by lazy { folder(CSIDL_DESKTOPDIRECTORY, "Desktop") }
    val documents: File by lazy { folder(CSIDL_PERSONAL, "Documents") }
    val pictures: File by lazy { folder(CSIDL_MYPICTURES, "Pictures") }
    val videos: File by lazy { folder(CSIDL_MYVIDEO, "Videos") }
    val music: File by lazy { folder(CSIDL_MYMUSIC, "Music") }

    /** The OneDrive roots (personal and/or work/school), as published by the OneDrive client. */
    val oneDrives: List<File> by lazy {
        listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial")
            .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank)?.let(::File) }
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath.lowercase() }
    }
}
