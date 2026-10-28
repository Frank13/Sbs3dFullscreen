import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

// "mp4" matches CameraSync3D's isVideoFilename (Playlist.kt), the only video type PlaylistItem's
// isVideo flag recognizes - keeping the gallery scan to the same set avoids picking up a video the
// playlist would then mislabel as a photo.
private val galleryMediaExtensions = setOf("jpg", "jpeg", "mpo", "mp4")

/**
 * One subdirectory (recursively found under the chosen gallery root) that contains at least one
 * photo or video, shown as a collapsible section on GalleryScreen. [relativePath] is empty for
 * files directly inside the chosen root.
 */
data class GalleryGroup(val relativePath: String, val displayName: String, val files: List<File>)

/** Recursively scans [root] for JPEGs/MPOs/mp4s, grouped by the immediate subdirectory that contains them. */
private fun scanGalleryDirectory(root: File): List<GalleryGroup> =
    root.walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in galleryMediaExtensions }
        .groupBy { it.parentFile }
        .map { (dir, files) ->
            val relativePath = dir.relativeTo(root).path.replace(File.separatorChar, '/')
            GalleryGroup(
                relativePath = relativePath,
                displayName = relativePath.ifEmpty { root.name },
                files = files.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.relativePath }

/**
 * Holds GalleryScreen's state (the scanned directory, its collapsible groups, and which photo to
 * scroll back into view on return) plus the logic to mutate it - pulled out of AppViewModel for
 * the same reason PhotoToolsState was: a self-contained cluster of fields/methods used from
 * exactly one place. Owned by AppViewModel as `gallery`. AppViewModel itself still owns the actual
 * Screen transitions (Screen.Gallery/Screen.ImageView) and the cross-cutting nav state (imageFiles/
 * currentImageIndex) touched when opening a photo from the gallery, since those aren't
 * gallery-specific - see AppViewModel.openGallery/openGalleryImage/closeImageView.
 */
class GalleryState {
    var root by mutableStateOf<File?>(null)
        private set
    var groups by mutableStateOf<List<GalleryGroup>>(emptyList())
        private set
    var expandedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    // True while ImageView was entered from GalleryScreen - see AppViewModel.closeImageView,
    // which routes back here instead of Welcome/PlaylistList.
    var enteredFromGallery by mutableStateOf(false)
        private set

    // Set on returning from ImageView to Gallery, to whichever photo was actually on screen -
    // which may differ from the one originally tapped if Left/Right was used inside ImageView.
    // GalleryScreen consumes this (consumeScrollTarget) to scroll that photo back into view
    // instead of leaving the list wherever it happened to be scrolled to.
    var scrollTarget by mutableStateOf<File?>(null)
        private set

    // True while the Gallery is being used as a multi-select photo picker (opened from
    // PlaylistEdit's "+" button - see AppViewModel.openGalleryForPicking) rather than for
    // fullscreen browsing. GalleryScreen shows checkboxes and a selection-count title/confirm
    // button instead of opening ImageView on tap.
    var pickerMode by mutableStateOf(false)
        private set

    var selectedFiles by mutableStateOf<Set<File>>(emptySet())
        private set

    /** Recursively scans [folder] for images - see AppViewModel.openGallery/openGalleryForPicking. */
    fun open(folder: File, pickerMode: Boolean = false) {
        root = folder
        val scanned = scanGalleryDirectory(folder)
        groups = scanned
        expandedGroups = scanned.map { it.relativePath }.toSet()
        this.pickerMode = pickerMode
        selectedFiles = emptySet()
    }

    /**
     * Re-scans [root] so files saved (a Save in any ImageView edit tool) or deleted while in
     * ImageView show up/disappear here - [open]'s scan is otherwise never repeated. Groups the
     * user had collapsed stay collapsed; groups that didn't exist before start expanded, like in
     * [open].
     */
    fun refresh() {
        val folder = root ?: return
        val previousPaths = groups.map { it.relativePath }.toSet()
        val scanned = scanGalleryDirectory(folder)
        groups = scanned
        expandedGroups = scanned.map { it.relativePath }
            .filter { it in expandedGroups || it !in previousPaths }
            .toSet()
    }

    fun close() {
        root = null
        groups = emptyList()
        expandedGroups = emptySet()
        pickerMode = false
        selectedFiles = emptySet()
    }

    /** Toggles [file]'s selection while in picker mode - see GalleryScreen's checkbox overlay. */
    fun toggleSelection(file: File) {
        selectedFiles = if (file in selectedFiles) selectedFiles - file else selectedFiles + file
    }

    fun toggleGroup(relativePath: String) {
        expandedGroups = if (relativePath in expandedGroups) expandedGroups - relativePath else expandedGroups + relativePath
    }

    /** Clears [scrollTarget] once GalleryScreen has scrolled to it, so a later return to the same
     *  photo (null -> file) still re-triggers the scroll instead of being a no-op change. */
    fun consumeScrollTarget() {
        scrollTarget = null
    }

    /** Marks ImageView as entered from Gallery - see AppViewModel.openGalleryImage. */
    fun markEntered() {
        enteredFromGallery = true
    }

    /** Marks the return trip to Gallery, recording [target] to scroll back to - see AppViewModel.closeImageView. */
    fun markReturnedTo(target: File?) {
        enteredFromGallery = false
        scrollTarget = target
    }
}
