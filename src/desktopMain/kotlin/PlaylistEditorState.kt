import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import fr.camera3d.camera.feature_playlists.domain.isVideoFilename
import java.io.File

/**
 * Holds the PlaylistList/PlaylistEdit/PlaylistItem screens' state (the scanned playlists, the one
 * open for editing, which of its photos is open) and the CRUD logic that reads/writes them on disk
 * via DesktopPlaylistStorage - pulled out of AppViewModel for the same reason GalleryState/
 * ShareState/PhotoToolsState were: a large, self-contained cluster of fields/methods AppViewModel's
 * own navigation logic doesn't otherwise touch. Owned by AppViewModel as `playlistEditor`.
 *
 * AppViewModel itself still owns Screen transitions and starting an actual slideshow (imageFiles/
 * currentImageIndex/playingPlaylist - see AppViewModel.onPlaylistChosen/playPlaylist/
 * playEditingPlaylist), since those are core navigation state shared with the plain-file-selection
 * path, not playlist-editor-specific.
 */
class PlaylistEditorState {
    // Resolved lazily (not on every access) so the one-time rename of the pre-rebrand
    // Pictures/sbs3dFullscreen folder happens at most once per run - existing users keep their
    // playlists. Skipped if the new folder already exists, so nothing is ever overwritten.
    private val playlistsRoot: File by lazy {
        val pictures = File(System.getProperty("user.home"), "Pictures")
        val root = File(pictures, "SBS 3D Viewer")
        val legacy = File(pictures, "sbs3dFullscreen")
        if (!root.exists() && legacy.isDirectory) legacy.renameTo(root)
        root
    }

    // Playlists found under playlistsRoot, shown on the PlaylistList screen.
    var all by mutableStateOf<List<Playlist>>(emptyList())
        private set

    // The playlist currently open in the PlaylistEdit screen (name/photos/etc.), null otherwise.
    var editing by mutableStateOf<Playlist?>(null)
        private set

    // Index into editing.photos of the photo open in the PlaylistItem screen, null otherwise.
    var editingItemIndex by mutableStateOf<Int?>(null)
        private set

    // True while PlaylistEdit/ImageView was entered from the PlaylistList screen (as opposed to
    // Welcome directly), so closing them returns to PlaylistList (refreshed) instead of Welcome.
    var enteredFromList by mutableStateOf(false)
        private set

    private fun loadAllFromRoot(): List<Playlist> {
        val storage = DesktopPlaylistStorage(playlistsRoot)
        val dirs = playlistsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.sortedBy { it.name.lowercase() }.map { Playlist.loadPlaylist(storage, it.name) }
    }

    /** Scans playlistsRoot for playlist folders - see AppViewModel.openPlaylistList. */
    fun openList() {
        all = loadAllFromRoot()
        enteredFromList = false
    }

    /** Re-scans playlistsRoot without leaving the PlaylistList screen. */
    fun refreshList() {
        all = loadAllFromRoot()
    }

    /** Opens the given playlist (picked from the list screen) in the editor. */
    fun openForEdit(playlist: Playlist) {
        editing = playlist
        enteredFromList = true
    }

    /** Marks a slideshow as launched from the list screen ("Play" button) rather than the editor -
     *  see AppViewModel.playPlaylist. */
    fun markEnteredFromList() {
        enteredFromList = true
    }

    /** Where closing PlaylistEdit/(a playlist-launched) ImageView should land, refreshing the list
     *  if it's the target - see AppViewModel.closeImageView/closePlaylistEdit. */
    fun returnFromChildScreen(): Screen {
        val target = if (enteredFromList) Screen.PlaylistList else Screen.Welcome
        enteredFromList = false
        if (target == Screen.PlaylistList) all = loadAllFromRoot()
        return target
    }

    /**
     * Copies an externally-selected playlist folder (as created/synced by CameraSync3D, or a plain
     * folder of JPEGs) into playlistsRoot and opens it for editing, mirroring startCreate's
     * create-then-edit flow. Returns null without copying anything if a playlist with the same
     * folder name already exists in the root.
     */
    fun importFolder(folder: File): Playlist? {
        // Sanitized like startCreate's dirName, so an imported folder can never end up with a
        // different on-disk name (e.g. spaces) than a playlist created directly from the same
        // display name would get - otherwise the two could coexist as distinct directories that
        // both normalize to the same Playlist.getDirName(), which broke the PlaylistList's
        // LazyColumn item keys (duplicate key crash).
        val destination = File(playlistsRoot, sanitizedDirName(folder.name))
        if (destination.exists()) return null
        playlistsRoot.mkdirs()
        folder.copyRecursively(destination)

        val storage = DesktopPlaylistStorage(playlistsRoot)
        val playlist = Playlist.loadPlaylist(storage, destination.name)
        editing = playlist
        Analytics.logEvent("playlist_created", mapOf("source" to "import"))
        return playlist
    }

    /** Whether a new playlist named [name] wouldn't collide with an existing one already on disk. */
    fun canCreate(name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return true
        return !File(playlistsRoot, sanitizedDirName(trimmedName)).exists()
    }

    /**
     * Creates a playlist folder under Pictures/SBS 3D Viewer/{name} and opens it for editing,
     * mirroring CameraSync3D's create-then-add-photos flow. Assumes the caller already checked
     * [canCreate]; if the sanitized name still collides with an existing playlist, that existing
     * playlist is reopened instead. Returns false (no-op) for a blank name.
     */
    fun startCreate(name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return false
        // Strips characters that are invalid in Windows folder names; the display name (with
        // spaces/accents intact) is kept separately in the Playlist itself.
        val dirName = sanitizedDirName(trimmedName)
        val folder = File(playlistsRoot, dirName)
        folder.mkdirs()

        val storage = DesktopPlaylistStorage(playlistsRoot)
        val playlist = if (storage.indexFileExists(dirName)) {
            Playlist.loadPlaylist(storage, dirName)
        } else {
            Playlist(name = trimmedName, absolutePath = folder.absolutePath).also { it.save(storage) }
                .also { Analytics.logEvent("playlist_created", mapOf("source" to "new")) }
        }
        editing = playlist
        return true
    }

    /** Copies the given files (chosen directly via a multi-select file dialog) into the playlist being edited and appends them to its index. */
    fun addPhotos(files: List<File>) {
        val playlist = editing ?: return
        val folder = File(playlist.absolutePath)
        val storage = DesktopPlaylistStorage(folder.parentFile ?: folder)
        // Filenames already in the playlist (updated as each file below is processed) - so
        // importing the same file twice, or two different source files that happen to share a
        // name, get distinct "name (2).ext" copies instead of colliding: two PlaylistItems with
        // the same filename crash PlaylistScreen's LazyColumn (duplicate key) and would also have
        // the second import silently overwrite the first one's file content on disk.
        val existingNames = playlist.photos.mapTo(mutableSetOf()) { it.filename }
        val copiedItems = files.map { src ->
            val destName = uniqueDestFilename(src.name, existingNames)
            existingNames += destName
            val dest = File(folder, destName)
            if (src.canonicalFile != dest.canonicalFile) {
                src.copyTo(dest, overwrite = true)
            }
            // toPath().toUri() (not File.toURI()) - on Windows, File.toURI() emits the ambiguous
            // "file:/C:/..." single-slash form, which coil3's Uri parser mis-parses: it treats the
            // drive letter's ':' as a second scheme separator and drops "C:" from the path, so the
            // thumbnail fails to load. Path.toUri() emits the unambiguous "file:///C:/..." form.
            val isVideo = isVideoFilename(destName)
            PlaylistItem(destName, dest.toPath().toUri().toString(), isHalfWidth = isVideo, isVideo = isVideo)
        }
        val updatedPlaylist = playlist.copy(photos = playlist.photos + copiedItems)
        updatedPlaylist.save(storage)
        editing = updatedPlaylist
        if (copiedItems.isNotEmpty()) {
            Analytics.logEvent("playlist_photos_added", mapOf("count" to copiedItems.size))
        }
    }

    /** Appends " (2)", " (3)", ... before the extension until [proposedName] no longer collides with [existingNames]. */
    private fun uniqueDestFilename(proposedName: String, existingNames: Set<String>): String {
        if (proposedName !in existingNames) return proposedName
        val dotIndex = proposedName.lastIndexOf('.')
        val base = if (dotIndex >= 0) proposedName.substring(0, dotIndex) else proposedName
        val ext = if (dotIndex >= 0) proposedName.substring(dotIndex) else ""
        var n = 2
        while ("$base ($n)$ext" in existingNames) n++
        return "$base ($n)$ext"
    }

    /**
     * Copies the given audio files into the playlist folder and appends them to its soundtrack
     * (played one after the other during the slideshow). Returns how many were added.
     */
    fun addSoundtrack(files: List<File>): Int {
        val playlist = editing ?: return 0
        val folder = File(playlist.absolutePath)
        val taken = playlist.soundtrack.toMutableSet()
        val addedNames = mutableListOf<String>()
        for (src in files) {
            // Unlike photos, a soundtrack name also must not collide with any other file of the folder
            // (Android picks "1_name.mp3", "2_name.mp3"... the same way).
            var dest = File(folder, src.name)
            var count = 1
            while (dest.exists() || dest.name in taken) {
                dest = File(folder, "${count}_${src.name}")
                count++
            }
            try {
                src.copyTo(dest)
            } catch (e: Exception) {
                continue
            }
            taken += dest.name
            addedNames += dest.name
        }
        if (addedNames.isNotEmpty()) {
            save(playlist.copy(soundtrack = playlist.soundtrack + addedNames))
            Analytics.logEvent("playlist_music_added", mapOf("count" to addedNames.size))
        }
        return addedNames.size
    }

    /** Removes a track from the soundtrack of the playlist being edited and deletes its file. */
    fun removeSoundtrackItem(index: Int) {
        val playlist = editing ?: return
        val filename = playlist.soundtrack.getOrNull(index) ?: return
        save(playlist.copy(soundtrack = playlist.soundtrack.filterIndexed { i, _ -> i != index }))
        File(playlist.absolutePath, filename).delete()
    }

    /** Files (in playback order) for the playlist currently open in the editor - see
     *  AppViewModel.playEditingPlaylist. */
    fun editingPhotoFiles(): List<File> = (editing ?: return emptyList()).photos.map { playlistItemFile(it.imageUriString) }

    /**
     * Sanitizes a playlist name into the folder name it would get, mirroring startCreate. Must
     * also strip spaces to match Playlist.getDirName() (shared with Android), which strips spaces
     * from the folder name when deriving the dir name used to read/write the index file -
     * otherwise the folder created here and the one save()/load() target end up different.
     */
    private fun sanitizedDirName(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").replace(" ", "").ifEmpty { "Playlist" }

    /** Whether newName's folder doesn't collide with another playlist already on disk. */
    fun canRename(newName: String): Boolean {
        val playlist = editing ?: return true
        if (newName.equals(playlist.name, ignoreCase = true)) return true
        val root = File(playlist.absolutePath).parentFile ?: return true
        return !File(root, sanitizedDirName(newName)).exists()
    }

    /** Renames the playlist being edited: moves its folder on disk and updates the index. */
    fun rename(newName: String): Boolean {
        val playlist = editing ?: return false
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty() || !canRename(trimmedName)) return false
        val currentFolder = File(playlist.absolutePath)
        val root = currentFolder.parentFile ?: return false
        val newFolder = File(root, sanitizedDirName(trimmedName))
        if (newFolder != currentFolder && !currentFolder.renameTo(newFolder)) return false
        save(playlist.copy(name = trimmedName, absolutePath = newFolder.absolutePath))
        return true
    }

    /** Updates the slideshow default duration between slides, in seconds (1..60, like CameraSync3D). */
    fun modifyDefaultDuration(newDurationS: Long): Boolean {
        val playlist = editing ?: return false
        if (newDurationS !in 1..60) return false
        save(playlist.copy(defaultDurationS = newDurationS))
        return true
    }

    fun modifyIsAutomated(newValue: Boolean): Boolean {
        save((editing ?: return false).copy(isAutomated = newValue))
        return true
    }

    fun modifySubtitle(newValue: String): Boolean {
        save((editing ?: return false).copy(subtitle = newValue))
        return true
    }

    fun modifyTitleZPercent(newValue: Float): Boolean {
        save((editing ?: return false).copy(titleZPercent = newValue))
        return true
    }

    fun modifySubtitleZPercent(newValue: Float): Boolean {
        save((editing ?: return false).copy(subtitleZPercent = newValue))
        return true
    }

    fun modifyTitleStyle(newValue: TextStyleConfig): Boolean {
        save((editing ?: return false).copy(titleStyle = newValue))
        return true
    }

    fun modifySubtitleStyle(newValue: TextStyleConfig): Boolean {
        save((editing ?: return false).copy(subtitleStyle = newValue))
        return true
    }

    /** Applies a fully-reordered photo list (e.g. after drag-and-drop) and saves to disk. */
    fun applyPhotosReorder(newPhotos: List<PlaylistItem>) {
        save((editing ?: return).copy(photos = newPhotos))
    }

    /** Opens the given photo (picked from the PlaylistEdit screen's list) in the PlaylistItem screen. */
    fun openItem(index: Int) {
        editingItemIndex = index
    }

    fun closeItem() {
        editingItemIndex = null
    }

    /** Applies [transform] to the photo open in the PlaylistItem screen and saves to disk. */
    private fun modifyEditingItem(transform: (PlaylistItem) -> PlaylistItem): Boolean {
        val playlist = editing ?: return false
        val index = editingItemIndex ?: return false
        val photo = playlist.photos.getOrNull(index) ?: return false
        val newPhotos = playlist.photos.toMutableList().apply { this[index] = transform(photo) }.toList()
        save(playlist.copy(photos = newPhotos))
        return true
    }

    fun modifyItemComment(newValue: String): Boolean = modifyEditingItem { it.copy(comment = newValue) }

    fun modifyItemCommentZPercent(newValue: Float): Boolean = modifyEditingItem { it.copy(commentZPercent = newValue) }

    fun modifyItemDuration(newValue: Int): Boolean = modifyEditingItem { it.copy(durationS = newValue) }

    fun modifyItemHalfWidth(newValue: Boolean) {
        modifyEditingItem { it.copy(isHalfWidth = newValue) }
    }

    fun modifyItemMuted(newValue: Boolean) {
        modifyEditingItem { it.copy(isMuted = newValue) }
    }

    /** Deletes the photo open in the PlaylistItem screen: removes it from the playlist and from disk. */
    fun deleteItem(): Boolean {
        val playlist = editing ?: return false
        val index = editingItemIndex ?: return false
        val photo = playlist.photos.getOrNull(index) ?: return false
        val newPhotos = playlist.photos.toMutableList().apply { removeAt(index) }.toList()
        save(playlist.copy(photos = newPhotos))
        playlistItemFile(photo.imageUriString).delete()
        return true
    }

    /** Deletes the playlist being edited (folder and all) from disk. */
    fun delete(): Boolean {
        val playlist = editing ?: return false
        val deleted = File(playlist.absolutePath).deleteRecursively()
        if (deleted) {
            editing = null
        }
        return deleted
    }

    private fun save(updated: Playlist) {
        val folder = File(updated.absolutePath)
        val storage = DesktopPlaylistStorage(folder.parentFile ?: folder)
        updated.save(storage)
        editing = updated
        Analytics.logEvent("playlist_saved")
    }

    fun closeEdit() {
        editing = null
    }
}
