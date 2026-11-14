import java.awt.Desktop
import java.io.File

/**
 * Moves [file] (a file or a whole folder) to the Windows Recycle Bin instead of deleting it
 * permanently, so a mistaken delete can be restored. Returns false (file left in place) when the
 * platform has no trash support or the move fails - e.g. a locked file or an online-only OneDrive
 * file whose provider isn't running - never falls back to a permanent delete, and never throws.
 */
fun moveToRecycleBin(file: File): Boolean = try {
    Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
        Desktop.getDesktop().moveToTrash(file)
} catch (e: Exception) {
    false
}
