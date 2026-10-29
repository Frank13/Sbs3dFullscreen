import java.awt.Dimension
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.filechooser.FileSystemView

/**
 * Multi-select file picker for the given extensions.
 *
 * Uses Swing's JFileChooser rather than java.awt.FileDialog: FileDialog opens Windows' legacy
 * comdlg32 common dialog, which isn't itself per-monitor DPI aware. Since this app's process is
 * (the JDK 9+ default), Windows bitmap-stretches that dialog to the monitor's scale, producing
 * blurry/mis-sized toolbar icons and oversized thumbnail padding - a jpackage/JVM limitation with
 * no supported override hook. JFileChooser is rendered entirely by Swing, so it always scales
 * correctly, at the cost of generic file-type icons instead of native photo thumbnails - the
 * [ImagePreviewAccessory] below adds a manual preview panel to compensate.
 */
fun chooseFiles(window: java.awt.Window, title: String, extensions: Array<String>): List<File> =
    chooseFiles(window, title, extensions, lastFilesDirectory)

/** Same as [chooseFiles], opening in (and remembering) the Music folder - for the playlist soundtrack. */
fun chooseMusicFiles(window: java.awt.Window, title: String, extensions: Array<String>): List<File> =
    chooseFiles(window, title, extensions, lastMusicDirectory)

private fun chooseFiles(window: java.awt.Window, title: String, extensions: Array<String>, directory: LastDirectoryPreference): List<File> {
    val chooser = newFileChooser(directory.getOrDefault())
    chooser.dialogTitle = title
    chooser.isMultiSelectionEnabled = true
    chooser.fileFilter = FileNameExtensionFilter(extensions.joinToString(", ") { "*.$it" }, *extensions)
    chooser.accessory = ImagePreviewAccessory(chooser)
    val approved = chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION
    directory.save(chooser.currentDirectory)
    return if (approved) {
        chooser.selectedFiles.toList()
    } else {
        emptyList()
    }
}

/**
 * Single-folder picker (e.g. GalleryScreen's "Open 3D image directory"), remembering the last
 * folder chosen independently of [chooseFiles]'s own memory.
 */
fun chooseDirectory(window: java.awt.Window, title: String): File? {
    val chooser = newFileChooser(lastGalleryDirectory.getOrDefault())
    chooser.dialogTitle = title
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    val approved = chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION
    lastGalleryDirectory.save(chooser.selectedFile ?: chooser.currentDirectory)
    return if (approved) chooser.selectedFile else null
}

/** Every JFileChooser in the app must be created through this, see [SafeFileSystemView]. */
fun newFileChooser(directory: File? = null): JFileChooser = JFileChooser(directory, SafeFileSystemView)

/**
 * JFileChooser's default Windows FileSystemView goes through sun.awt.shell.Win32ShellFolder2 (COM
 * IShellFolder), which throws `InternalError: Unable to bind <folder> to parent` for folders the
 * shell can't bind (a known JDK bug, seen in production on a Desktop folder), killing the
 * chooser's loader thread. This view lists real folders with plain java.io instead - so the
 * entries are never ShellFolders - and only keeps the shell for virtual folders (This PC, ...),
 * with every remaining shell call falling back to a plain java.io answer on InternalError.
 * Being a non-default view also makes Swing's FilePane/combo box skip their own ShellFolder use.
 * Trade-off: Windows .lnk shortcuts show as plain files instead of navigable folders.
 */
private object SafeFileSystemView : FileSystemView() {
    private val shell: FileSystemView = getFileSystemView()

    private inline fun <T> safe(fallback: () -> T, block: () -> T): T =
        try {
            block()
        } catch (_: InternalError) {
            fallback()
        }

    override fun getFiles(dir: File?, useFileHiding: Boolean): Array<File> {
        if (dir == null) return emptyArray()
        if (!safe({ true }) { shell.isFileSystem(dir) }) {
            return safe({ emptyArray() }) { shell.getFiles(dir, useFileHiding) }
        }
        val files = File(dir.path).listFiles() ?: return emptyArray()
        return if (useFileHiding) files.filterNot { it.isHidden }.toTypedArray() else files
    }

    override fun createNewFolder(containingDir: File?): File? = shell.createNewFolder(containingDir)
    override fun isRoot(f: File?): Boolean = safe({ false }) { shell.isRoot(f) }
    override fun isTraversable(f: File?): Boolean? = safe({ f?.isDirectory }) { shell.isTraversable(f) }
    override fun getSystemDisplayName(f: File?): String? = safe({ f?.name }) { shell.getSystemDisplayName(f) }
    override fun getSystemTypeDescription(f: File?): String? = safe({ null }) { shell.getSystemTypeDescription(f) }
    override fun getSystemIcon(f: File?): Icon? = safe({ null }) { shell.getSystemIcon(f) }
    override fun isParent(folder: File?, file: File?): Boolean = safe({ file?.parentFile == folder }) { shell.isParent(folder, file) }
    override fun getChild(parent: File?, fileName: String?): File? = safe({ File(parent, fileName) }) { shell.getChild(parent, fileName) }
    override fun isFileSystem(f: File?): Boolean = safe({ true }) { shell.isFileSystem(f) }
    override fun isHiddenFile(f: File?): Boolean = safe({ f?.isHidden == true }) { shell.isHiddenFile(f) }
    override fun isFileSystemRoot(dir: File?): Boolean = safe({ false }) { shell.isFileSystemRoot(dir) }
    override fun isDrive(dir: File?): Boolean = safe({ false }) { shell.isDrive(dir) }
    override fun isFloppyDrive(dir: File?): Boolean = safe({ false }) { shell.isFloppyDrive(dir) }
    override fun isComputerNode(dir: File?): Boolean = safe({ false }) { shell.isComputerNode(dir) }
    override fun isLink(file: File?): Boolean = safe({ false }) { shell.isLink(file) }
    override fun getLinkLocation(file: File?): File? = safe({ null }) { shell.getLinkLocation(file) }
    override fun getRoots(): Array<File> = safe({ File.listRoots() }) { shell.roots }
    override fun getHomeDirectory(): File = safe({ File(System.getProperty("user.home")) }) { shell.homeDirectory }
    override fun getDefaultDirectory(): File = safe({ File(System.getProperty("user.home")) }) { shell.defaultDirectory }
    override fun createFileObject(dir: File?, filename: String?): File = safe({ File(dir, filename) }) { shell.createFileObject(dir, filename) }
    override fun createFileObject(path: String?): File = safe({ File(path) }) { shell.createFileObject(path) }
    override fun getParentDirectory(dir: File?): File? = safe({ dir?.parentFile }) { shell.getParentDirectory(dir) }
}

private val lastFilesDirectory = LastDirectoryPreference("lastDirectory")
private val lastGalleryDirectory = LastDirectoryPreference("lastGalleryDirectory")
private val lastMusicDirectory = LastDirectoryPreference("lastMusicDirectory", defaultFolderName = "Music")

/**
 * Remembers the last folder browsed under a given [key], defaulting to the user's Pictures folder
 * on first launch. Falls back gracefully (Pictures, then home) if a remembered folder no longer
 * exists - e.g. it was since deleted or was on a removable/network drive that isn't mounted.
 */
private class LastDirectoryPreference(private val key: String, private val defaultFolderName: String = "Pictures") {
    private val prefs = Preferences.userNodeForPackage(LastDirectoryPreference::class.java)

    fun getOrDefault(): File {
        val saved = prefs.get(key, null)?.let(::File)
        val pictures = File(System.getProperty("user.home"), defaultFolderName)
        val home = File(System.getProperty("user.home"))
        return sequenceOf(saved, pictures, home).filterNotNull().firstOrNull { it.isDirectory } ?: home
    }

    fun save(directory: File?) {
        if (directory != null && directory.isDirectory) {
            prefs.put(key, directory.absolutePath)
        }
    }
}

/** Side panel showing a scaled preview of the currently-selected image; blank for non-image files (e.g. videos). */
private class ImagePreviewAccessory(chooser: JFileChooser) : JPanel() {
    private val label = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        verticalAlignment = SwingConstants.CENTER
    }
    private var loadSeq = 0

    init {
        preferredSize = Dimension(180, 180)
        add(label)
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILE_CHANGED_PROPERTY) {
            updatePreview(chooser.selectedFile)
        }
        chooser.addPropertyChangeListener(JFileChooser.SELECTED_FILES_CHANGED_PROPERTY) {
            updatePreview(chooser.selectedFiles?.firstOrNull())
        }
    }

    private fun updatePreview(file: File?) {
        val seq = ++loadSeq
        label.icon = null
        label.text = null
        if (file == null || !file.isFile) return
        // Decoding full-res JPEGs off the EDT keeps the dialog responsive while browsing.
        Thread {
            val image = runCatching { ImageIO.read(file) }.getOrNull()
            SwingUtilities.invokeLater {
                if (seq != loadSeq) return@invokeLater
                if (image != null) {
                    label.icon = ImageIcon(image.scaledToFit(160, 160))
                } else {
                    label.text = "No preview"
                }
            }
        }.start()
    }
}

private fun BufferedImage.scaledToFit(maxWidth: Int, maxHeight: Int): Image {
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height, 1.0)
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    return getScaledInstance(w, h, Image.SCALE_SMOOTH)
}
