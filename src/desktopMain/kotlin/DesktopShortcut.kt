import java.io.File

/**
 * Creates a desktop shortcut for the Store-installed (MSIX) build, which Windows never adds on its
 * own (MSIX has no manifest option for it; only the MSI/EXE installers create one).
 *
 * The shortcut can't point at the exe: it lives in the protected WindowsApps folder and launching
 * it directly would bypass the package identity. It targets the package's AppsFolder entry instead,
 * built from the package family name read off the install folder
 * (`<Name>_<version>_<arch>__<publisherId>` -> `<Name>_<publisherId>`).
 */
object DesktopShortcut {
    // Must match the Application Id in packaging/msix/AppxManifest.xml.
    private const val ApplicationId = "sbs3Dfullscreen"
    private const val ShortcutName = "SBS 3D Viewer"
    private val exePath: String? = ProcessHandle.current().info().command().orElse(null)

    private val packageFamilyName: String? by lazy {
        val exe = exePath ?: return@lazy null
        val folder = generateSequence(File(exe).parentFile) { it.parentFile }
            .firstOrNull { it.parentFile?.name.equals("WindowsApps", ignoreCase = true) }
            ?: return@lazy null
        val parts = folder.name.split("__")
        val name = parts.firstOrNull()?.substringBefore('_')
        val publisherId = parts.getOrNull(1)
        if (name.isNullOrBlank() || publisherId.isNullOrBlank()) null else "${name}_$publisherId"
    }

    // Not assumed to be ~/Desktop: OneDrive backup can redirect it, see WindowsFolders.
    private val shortcutFile: File by lazy { File(WindowsFolders.desktop, "$ShortcutName.lnk") }

    /** Only true when running inside the MSIX package, the sole case where the shortcut is needed. */
    val isAvailable: Boolean get() = packageFamilyName != null

    /** Whether the shortcut [create] makes is already on the desktop (under its default name). */
    fun exists(): Boolean = shortcutFile.exists()

    /** Returns true if the shortcut was created. Blocking: call from a background dispatcher. */
    fun create(): Boolean {
        val family = packageFamilyName ?: return false
        val exe = exePath ?: return false
        val script = """
            ${'$'}link = (New-Object -ComObject WScript.Shell).CreateShortcut('${shortcutFile.path.replace("'", "''")}')
            ${'$'}link.TargetPath = 'explorer.exe'
            ${'$'}link.Arguments = 'shell:AppsFolder\$family!$ApplicationId'
            ${'$'}link.IconLocation = '${exe.replace("'", "''")},0'
            ${'$'}link.Save()
        """.trimIndent()
        return try {
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readAllBytes()
            process.waitFor() == 0 && shortcutFile.exists()
        } catch (_: Exception) {
            false
        }
    }
}
