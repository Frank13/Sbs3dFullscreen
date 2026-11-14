import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.camera3d.camera.feature_playlists.domain.CreditsPlacement
import fr.camera3d.camera.feature_playlists.domain.FullscreenScroll
import fr.camera3d.camera.feature_playlists.domain.PLAYLIST_INDEX_FILENAME
import fr.camera3d.camera.feature_playlists.domain.PLAYLIST_INDEX_UNREADABLE_PREFIX
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import fr.camera3d.camera.feature_playlists.domain.isVideoFilename
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

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
 *
 * **Threading.** No disk I/O runs on the UI thread: the playlists folder can be on OneDrive, where a
 * write can block or throw ("The cloud file provider is not running"). Edits are optimistic - the
 * in-memory [editing] playlist changes at once (the synced Android composables expect an immediate
 * Boolean) and the disk work is queued on [writer], a single background thread that runs jobs
 * strictly in order, so a later edit, delete or list rescan always sees the earlier writes on disk.
 * Jobs never suspend: they post their UI-state updates with [onUi] (the AWT event thread Compose runs
 * on), which keeps that order too. A failed job is reported through [writeError] (a snackbar on
 * whichever playlist screen is showing) instead of escaping into the window's crash handler.
 */
class PlaylistEditorState(rootOverride: File? = null) {
    // Resolved lazily (not on every access) so the one-time rename of the pre-rebrand
    // Pictures/sbs3dFullscreen folder happens at most once per run - existing users keep their
    // playlists. Skipped if the new folder already exists, so nothing is ever overwritten.
    // [rootOverride] lets tests work in a temp folder instead of the user's real Pictures.
    // First touched from a writer job (see openList), never from the UI thread.
    private val playlistsRoot: File by lazy {
        if (rootOverride != null) return@lazy rootOverride
        val pictures = File(System.getProperty("user.home"), "Pictures")
        val root = File(pictures, "SBS 3D Viewer")
        val legacy = File(pictures, "sbs3dFullscreen")
        if (!root.exists() && legacy.isDirectory) legacy.renameTo(root)
        root
    }

    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "playlist-writer").apply { isDaemon = true }
    }
    private val writer = writerExecutor.asCoroutineDispatcher()

    // Playlists found under playlistsRoot, shown on the PlaylistList screen.
    var all by mutableStateOf<List<Playlist>>(emptyList())
        private set

    // True until the first scan requested by openList has filled [all], so the PlaylistList screen
    // doesn't flash its "no playlist" message meanwhile.
    var isLoadingList by mutableStateOf(false)
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

    // True while importFolder/addPhotos/addSoundtrack copy files (videos can be gigabytes) on a
    // background thread - drives Main.kt's CopyingFilesOverlay, which also blocks clicks meanwhile
    // so the playlist being copied into can't be closed/edited underneath the copy.
    var isCopying by mutableStateOf(false)
        private set

    /** A failed background playlist write, shown once by PlaylistWriteErrorSnackbar then [consumeWriteError]ed. */
    data class WriteError(val token: Int, val message: String)

    var writeError by mutableStateOf<WriteError?>(null)
        private set
    private var writeErrorCounter = 0

    fun consumeWriteError() {
        writeError = null
    }

    /** Runs [block] on the UI (AWT event) thread, after every UI update posted before it. */
    private fun onUi(block: () -> Unit) = SwingUtilities.invokeLater(block)

    /**
     * Queues [block] on [writer]. Any exception is reported through [writeError] rather than thrown;
     * [onFailure] (also run on [writer], so it may touch the disk) can then return a UI-thread fix-up,
     * e.g. reverting an optimistic change that didn't make it to disk.
     */
    private fun enqueue(what: String, onFailure: (() -> (() -> Unit)?)? = null, block: () -> Unit) {
        writerExecutor.execute {
            try {
                block()
            } catch (e: Exception) {
                System.err.println("Playlist $what failed: $e")
                Analytics.logEvent("app_error", mapOf("type" to "playlist_write", "action" to what))
                val fixUp = runCatching { onFailure?.invoke() }.getOrNull()
                onUi {
                    fixUp?.invoke()
                    writeError = WriteError(++writeErrorCounter, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /** Waits until every job queued so far, and the UI updates they posted, are done - for tests. */
    suspend fun awaitPendingWrites() {
        withContext(writer) {}
        withContext(Dispatchers.Main) {}
    }

    private suspend fun <T> copying(block: () -> T): T {
        isCopying = true
        try {
            return withContext(writer) { block() }
        } finally {
            isCopying = false
        }
    }

    private fun loadAllFromRoot(): List<Playlist> {
        val dirs = playlistsRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        return dirs.sortedBy { it.name.lowercase() }.map { load(playlistsRoot, it.name) }
    }

    /** Rescans the list after every write queued so far (so it can't show a stale playlist). */
    private fun reloadList() {
        enqueue("list") {
            val playlists = loadAllFromRoot()
            onUi {
                all = playlists
                isLoadingList = false
            }
        }
    }

    /**
     * Playlist.loadPlaylist, plus a desktop fix-up: its disk-scan fallback (createFromDisk, synced
     * from Android - used when there's no index yet, or one that can't be read) sets absolutePath to
     * the bare folder name, which save()/addPhotos would otherwise resolve against the process's
     * working directory instead of the playlist folder.
     */
    private fun load(root: File, dirName: String): Playlist {
        val playlist = Playlist.loadPlaylist(DesktopPlaylistStorage(root), dirName)
        return if (File(playlist.absolutePath).isAbsolute) playlist
        else playlist.copy(absolutePath = File(root, dirName).absolutePath)
    }

    /** Scans playlistsRoot for playlist folders - see AppViewModel.openPlaylistList. */
    fun openList() {
        if (all.isEmpty()) isLoadingList = true
        enteredFromList = false
        reloadList()
    }

    /** Re-scans playlistsRoot without leaving the PlaylistList screen. */
    fun refreshList() {
        reloadList()
    }

    /**
     * What PlaylistScreen's recovery banner shows for the playlist being edited, null when its index is
     * fine: either a kept `playlistIndex.unreadable-*.yaml` copy (see DesktopPlaylistStorage.preserveIndexAside),
     * or - while [editing] is still Playlist.indexUnreadable because that move failed - the unreadable
     * index itself, in place.
     */
    var indexRecovery by mutableStateOf<IndexRecovery?>(null)
        private set

    /**
     * @property file the kept copy, or the in-place index when ![keptAside]
     * @property restorable whether [file] now loads - a copy kept because OneDrive was offline, or
     *   because it held a field an older version rejected, restores fine; a truly damaged one doesn't
     */
    data class IndexRecovery(val file: File, val keptAside: Boolean, val restorable: Boolean)

    /** Opens the given playlist (picked from the list screen) in the editor. */
    fun openForEdit(playlist: Playlist) {
        beginEditing(playlist)
        enteredFromList = true
    }

    /**
     * Every way into the editor goes through here. A Playlist.indexUnreadable playlist gets its
     * unreadable index moved aside right away - rather than only on its first save (see [persist],
     * which still does it if this move fails) - so the banner can offer it for restore at once and
     * the copy doesn't depend on the user editing anything.
     */
    private fun beginEditing(playlist: Playlist) {
        editing = playlist
        indexRecovery = null
        enqueue("open") {
            var preserved = false
            if (playlist.indexUnreadable) {
                try {
                    storageFor(playlist).preserveIndexAside(playlist.getDirName())
                    Analytics.logEvent("app_error", mapOf("type" to "playlist_index_unreadable"))
                    preserved = true
                } catch (e: IOException) {
                    // kept in place: persist() tries again on the first save
                }
            }
            val recovery = computeIndexRecovery(playlist.copy(indexUnreadable = playlist.indexUnreadable && !preserved))
            onUi {
                if (isStillEditing(playlist)) {
                    if (preserved) editing = editing?.copy(indexUnreadable = false)
                    indexRecovery = recovery
                }
            }
        }
    }

    /** Whether [editing] is still the playlist (folder) [playlist] was snapshotted from. */
    private fun isStillEditing(playlist: Playlist) = editing?.absolutePath == playlist.absolutePath

    private fun storageFor(playlist: Playlist): DesktopPlaylistStorage {
        val folder = File(playlist.absolutePath)
        return DesktopPlaylistStorage(folder.parentFile ?: folder)
    }

    /** Reads what [indexRecovery] should be for [playlist] - disk I/O, so on [writer] only. */
    private fun computeIndexRecovery(playlist: Playlist): IndexRecovery? =
        if (playlist.indexUnreadable) {
            val index = File(playlist.absolutePath, PLAYLIST_INDEX_FILENAME)
            IndexRecovery(index, keptAside = false, restorable = fileIsValidIndex(index))
        } else {
            unreadableIndexCopies(File(playlist.absolutePath)).firstOrNull()?.let {
                IndexRecovery(it, keptAside = true, restorable = fileIsValidIndex(it))
            }
        }

    private fun fileIsValidIndex(file: File): Boolean =
        try {
            isValidPlaylistIndex(file.readText())
        } catch (e: IOException) {
            false
        }

    /**
     * The banner's Restore (a kept copy that loads again) / Retry (the in-place index, readable again)
     * button: puts that index back as the playlist's index and reloads the playlist from it. The index
     * it replaces (the photos-only one written since) isn't lost either: DesktopPlaylistStorage
     * keeps it as the .bak. Other kept copies are marked as handled so the banner doesn't come back for
     * an older one. Returns false (nothing changed) if the index still can't be read.
     */
    suspend fun restoreIndex(): Boolean {
        val recovery = indexRecovery ?: return false
        val playlist = editing ?: return false
        val folder = File(playlist.absolutePath)
        val root = folder.parentFile ?: return false
        val (reloaded, newRecovery) = withContext(writer) {
            val reloaded = try {
                var ok = true
                if (recovery.keptAside) {
                    val content = recovery.file.readText()
                    if (isValidPlaylistIndex(content)) {
                        DesktopPlaylistStorage(root).writeIndexFile(playlist.getDirName(), folder.absolutePath, content)
                        recovery.file.delete()
                        markCopiesKept(folder)
                    } else {
                        ok = false
                    }
                }
                if (ok) load(root, folder.name).takeUnless { it.indexUnreadable } else null
            } catch (e: IOException) {
                null
            }
            reloaded to computeIndexRecovery(reloaded ?: playlist)
        }
        if (!isStillEditing(playlist)) return false
        indexRecovery = newRecovery
        if (reloaded == null) return false
        editing = reloaded
        Analytics.logEvent("playlist_index_restored")
        return true
    }

    /** The banner's Dismiss button: keeps the copies (renamed `playlistIndex.kept-*`) but stops offering them. */
    fun dismissIndexRecovery() {
        val playlist = editing ?: return
        indexRecovery = null
        enqueue("dismiss_index_recovery") {
            markCopiesKept(File(playlist.absolutePath))
        }
    }

    private fun markCopiesKept(folder: File) {
        for (copy in unreadableIndexCopies(folder)) {
            copy.renameTo(File(folder, PLAYLIST_INDEX_KEPT_PREFIX + copy.name.removePrefix(PLAYLIST_INDEX_UNREADABLE_PREFIX)))
        }
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
        if (target == Screen.PlaylistList) reloadList()
        return target
    }

    /**
     * Copies an externally-selected playlist folder (as created/synced by CameraSync3D, or a plain
     * folder of JPEGs) into playlistsRoot and opens it for editing, mirroring startCreate's
     * create-then-edit flow. Returns null without copying anything if a playlist with the same
     * folder name already exists in the root. Throws [IOException] if a source file can't be read
     * (e.g. an online-only OneDrive file while OneDrive isn't running: "The cloud file provider is
     * not running"), after deleting the partially-copied destination so a retry isn't blocked by
     * the "already exists" check and no half-imported playlist shows up in the list.
     */
    suspend fun importFolder(folder: File): Playlist? {
        // Sanitized like startCreate's dirName, so an imported folder can never end up with a
        // different on-disk name (e.g. spaces) than a playlist created directly from the same
        // display name would get - otherwise the two could coexist as distinct directories that
        // both normalize to the same Playlist.getDirName(), which broke the PlaylistList's
        // LazyColumn item keys (duplicate key crash).
        val playlist = copying {
            val destination = File(playlistsRoot, sanitizedDirName(folder.name))
            if (destination.exists()) return@copying null
            playlistsRoot.mkdirs()
            try {
                folder.copyRecursively(destination)
            } catch (e: IOException) {
                destination.deleteRecursively()
                throw e
            }
            load(playlistsRoot, destination.name)
        } ?: return null
        beginEditing(playlist)
        Analytics.logEvent("playlist_created", mapOf("source" to "import"))
        return playlist
    }

    /** Whether a new playlist named [name] wouldn't collide with an existing one already on disk.
     *  A single exists() check, the only disk access left on the UI thread: the name dialog
     *  validates synchronously as the user types. */
    fun canCreate(name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return true
        return !File(playlistsRoot, sanitizedDirName(trimmedName)).exists()
    }

    /**
     * Creates a playlist folder under Pictures/SBS 3D Viewer/{name} and opens it for editing,
     * mirroring CameraSync3D's create-then-add-photos flow. Assumes the caller already checked
     * [canCreate]; if the sanitized name still collides with an existing playlist, that existing
     * playlist is reopened instead (once the queued job finds it). Returns false (no-op) for a blank
     * name.
     */
    fun startCreate(name: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return false
        // Strips characters that are invalid in Windows folder names; the display name (with
        // spaces/accents intact) is kept separately in the Playlist itself.
        val dirName = sanitizedDirName(trimmedName)
        val folder = File(playlistsRoot, dirName)
        val created = Playlist(name = trimmedName, absolutePath = folder.absolutePath)
        editing = created
        indexRecovery = null
        enqueue("create") {
            folder.mkdirs()
            val storage = DesktopPlaylistStorage(playlistsRoot)
            if (storage.indexFileExists(dirName)) {
                val existing = load(playlistsRoot, dirName)
                onUi { if (isStillEditing(created)) beginEditing(existing) }
            } else {
                created.save(storage)
                Analytics.logEvent("playlist_created", mapOf("source" to "new"))
            }
        }
        return true
    }

    /** Copies the given files (chosen directly via a multi-select file dialog) into the playlist being edited and appends them to its index. */
    suspend fun addPhotos(files: List<File>) {
        val playlist = editing ?: return
        val folder = File(playlist.absolutePath)
        // Filenames already in the playlist (updated as each file below is processed) - so
        // importing the same file twice, or two different source files that happen to share a
        // name, get distinct "name (2).ext" copies instead of colliding: two PlaylistItems with
        // the same filename crash PlaylistScreen's LazyColumn (duplicate key) and would also have
        // the second import silently overwrite the first one's file content on disk.
        val existingNames = playlist.photos.mapTo(mutableSetOf()) { it.filename }
        val copiedItems = copying { files.mapNotNull { src ->
            val destName = uniqueDestFilename(src.name, existingNames)
            val dest = File(folder, destName)
            if (src.canonicalFile != dest.canonicalFile) {
                // Skipped like addSoundtrack does, rather than crashing the whole import - e.g. an
                // online-only OneDrive file while OneDrive isn't running ("The cloud file provider
                // is not running"). The partial copy is removed so no orphan file is left behind.
                try {
                    src.copyTo(dest, overwrite = true)
                } catch (e: IOException) {
                    dest.delete()
                    return@mapNotNull null
                }
            }
            existingNames += destName
            // toPath().toUri() (not File.toURI()) - on Windows, File.toURI() emits the ambiguous
            // "file:/C:/..." single-slash form, which coil3's Uri parser mis-parses: it treats the
            // drive letter's ':' as a second scheme separator and drops "C:" from the path, so the
            // thumbnail fails to load. Path.toUri() emits the unambiguous "file:///C:/..." form.
            // toASCIIString(), not toString(): toString() leaves non-ASCII chars (e.g. an accented
            // playlist folder name) unescaped next to %-escapes like "SBS%203D%20Viewer", and
            // coil3's percent-decoder truncates each unescaped char to one byte (Uri.kt's
            // `code.toByte()`), turning "è" into "�" so the thumbnail's file is never found.
            val isVideo = isVideoFilename(destName)
            PlaylistItem(destName, dest.toPath().toUri().toASCIIString(), isHalfWidth = isVideo, isVideo = isVideo)
        } }
        if (copiedItems.isEmpty() || !isStillEditing(playlist)) return
        // From the current state, not the snapshot taken before the copy: nothing else could change
        // it meanwhile (CopyingFilesOverlay blocks clicks), but this keeps that true by construction.
        val current = editing ?: return
        save(current.copy(photos = current.photos + copiedItems))
        Analytics.logEvent("playlist_photos_added", mapOf("count" to copiedItems.size))
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
    suspend fun addSoundtrack(files: List<File>): Int {
        val playlist = editing ?: return 0
        val folder = File(playlist.absolutePath)
        val taken = playlist.soundtrack.toMutableSet()
        val addedNames = mutableListOf<String>()
        copying { for (src in files) {
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
        } }
        if (addedNames.isNotEmpty() && isStillEditing(playlist)) {
            val current = editing ?: return addedNames.size
            save(current.copy(soundtrack = current.soundtrack + addedNames))
            Analytics.logEvent("playlist_music_added", mapOf("count" to addedNames.size))
        }
        return addedNames.size
    }

    /** Removes a track from the soundtrack of the playlist being edited and deletes its file. */
    fun removeSoundtrackItem(index: Int) {
        val playlist = editing ?: return
        val filename = playlist.soundtrack.getOrNull(index) ?: return
        save(playlist.copy(soundtrack = playlist.soundtrack.filterIndexed { i, _ -> i != index })) {
            File(playlist.absolutePath, filename).delete()
        }
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

    /** Whether newName's folder doesn't collide with another playlist already on disk - an exists()
     *  check, kept synchronous for the same reason as [canCreate]. */
    fun canRename(newName: String): Boolean {
        val playlist = editing ?: return true
        if (newName.equals(playlist.name, ignoreCase = true)) return true
        val root = File(playlist.absolutePath).parentFile ?: return true
        return !File(root, sanitizedDirName(newName)).exists()
    }

    /**
     * Renames the playlist being edited: moves its folder on disk and updates the index. Optimistic
     * like every edit - the in-memory playlist (and each photo's URI, an absolute path resolved at load
     * time) points at the new folder at once, so edits queued after this one write there. If the move
     * fails (e.g. a file of the folder is open elsewhere), the playlist is reloaded from its old folder
     * and the error is shown.
     */
    fun rename(newName: String): Boolean {
        val playlist = editing ?: return false
        val trimmedName = newName.trim()
        if (trimmedName.isEmpty() || !canRename(trimmedName)) return false
        val currentFolder = File(playlist.absolutePath)
        val root = currentFolder.parentFile ?: return false
        val newFolder = File(root, sanitizedDirName(trimmedName))
        val renamed = playlist.copy(
            name = trimmedName,
            absolutePath = newFolder.absolutePath,
            photos = playlist.photos.map { it.copy(imageUriString = File(newFolder, it.filename).toPath().toUri().toASCIIString()) },
        )
        editing = renamed
        enqueue("rename", onFailure = {
            val reverted = load(root, currentFolder.name)
            ({ if (isStillEditing(renamed)) editing = reverted })
        }) {
            if (newFolder != currentFolder && !currentFolder.renameTo(newFolder)) {
                throw IOException("Cannot rename the folder ${currentFolder.name} to ${newFolder.name}")
            }
            persist(renamed)
        }
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

    /** Updates the max scrolling speed of "force fullscreen by scrolling" photos, in % of the screen per second (0..100, 0 = no limit, like CameraSync3D). */
    fun modifyScrollSpeedLimitPercent(newValue: Float): Boolean {
        val playlist = editing ?: return false
        if (newValue !in 0f..100f) return false
        save(playlist.copy(scrollSpeedLimitPercent = newValue))
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

    /** Updates the playlist credits (multi-line text rolling on the end slide or over the last image), "" for no credits. */
    fun modifyCredits(newValue: String): Boolean {
        save((editing ?: return false).copy(credits = newValue))
        return true
    }

    fun modifyCreditsZPercent(newValue: Float): Boolean {
        save((editing ?: return false).copy(creditsZPercent = newValue))
        return true
    }

    fun modifyCreditsStyle(newValue: TextStyleConfig): Boolean {
        save((editing ?: return false).copy(creditsStyle = newValue))
        return true
    }

    fun modifyCreditsPlacement(newValue: CreditsPlacement): Boolean {
        save((editing ?: return false).copy(creditsPlacement = newValue))
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

    fun modifyItemFullscreenScroll(newValue: FullscreenScroll) {
        modifyEditingItem { it.copy(fullscreenScroll = newValue) }
    }

    /** Deletes the photo open in the PlaylistItem screen: removes it from the playlist and from disk. */
    fun deleteItem(): Boolean {
        val playlist = editing ?: return false
        val index = editingItemIndex ?: return false
        val photo = playlist.photos.getOrNull(index) ?: return false
        val newPhotos = playlist.photos.toMutableList().apply { removeAt(index) }.toList()
        // The file is only deleted once the index no longer lists it.
        save(playlist.copy(photos = newPhotos)) {
            playlistItemFile(photo.imageUriString).delete()
        }
        return true
    }

    /**
     * Deletes the playlist being edited (folder and all) from disk - in the background, as a playlist
     * with videos can take a while to delete. The list screen the caller then returns to is rescanned
     * after the deletion, so it shows what is really left; a failure (e.g. a file open elsewhere) is
     * reported through [writeError].
     */
    fun delete(): Boolean {
        val playlist = editing ?: return false
        editing = null
        indexRecovery = null
        enqueue("delete") {
            if (!File(playlist.absolutePath).deleteRecursively()) {
                throw IOException("Some files of ${File(playlist.absolutePath).name} could not be deleted")
            }
        }
        return true
    }

    /**
     * Makes [updated] the playlist being edited now, and queues writing its index - then [afterWrite]
     * (e.g. deleting a file the index no longer lists), only if that write succeeded.
     */
    private fun save(updated: Playlist, afterWrite: () -> Unit = {}) {
        editing = updated
        Analytics.logEvent("playlist_saved")
        enqueue("save") {
            persist(updated)
            afterWrite()
            if (updated.indexUnreadable) {
                // persist() just moved the unreadable index aside: the banner can now offer it.
                val recovery = computeIndexRecovery(updated.copy(indexUnreadable = false))
                onUi {
                    if (isStillEditing(updated)) {
                        editing = editing?.copy(indexUnreadable = false)
                        indexRecovery = recovery
                    }
                }
            }
        }
    }

    /**
     * Writes [playlist]'s index - on [writer] only. A Playlist.indexUnreadable playlist (a disk-scan
     * fallback standing in for an index that couldn't be read at load time) gets its existing index
     * moved aside first (DesktopPlaylistStorage.preserveIndexAside), so the original metadata survives
     * as a playlistIndex.unreadable-*.yaml file instead of being overwritten by this stripped version;
     * if that move fails, nothing is written.
     */
    private fun persist(playlist: Playlist) {
        val storage = storageFor(playlist)
        if (playlist.indexUnreadable) {
            storage.preserveIndexAside(playlist.getDirName())
            Analytics.logEvent("app_error", mapOf("type" to "playlist_index_unreadable"))
        }
        playlist.copy(indexUnreadable = false).save(storage)
    }

    fun closeEdit() {
        editing = null
        indexRecovery = null
    }
}
