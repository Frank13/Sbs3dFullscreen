import fr.camera3d.camera.feature_playlists.domain.PLAYLIST_INDEX_FILENAME
import fr.camera3d.camera.feature_playlists.domain.PlaylistStorage
import fr.camera3d.camera.feature_playlists.domain.PlaylistType
import fr.camera3d.camera.feature_playlists.service.yamlImporter.getYamlPlaylistFromYaml
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Copy of the last index that parsed, kept next to it - see [DesktopPlaylistStorage.writeIndexFile]. */
internal const val PLAYLIST_INDEX_BACKUP_FILENAME = "$PLAYLIST_INDEX_FILENAME.bak"

/** Prefix of an unreadable index moved aside (never overwritten) - see [DesktopPlaylistStorage.preserveIndexAside]. */
internal const val PLAYLIST_INDEX_UNREADABLE_PREFIX = "playlistIndex.unreadable-"

/**
 * Desktop PlaylistStorage: no SAF/MediaStore concept exists here, so `rootDir` plays the role
 * Android's fixed "Playlists" folder does - `playlistDirName` is a bare folder name resolved
 * against it. This matters because Playlist.kt's own createFromDisk (synced verbatim from
 * Android) always reduces its `fullPlaylistFolder` argument down to a bare last-path-segment
 * name before calling listJpegs/getPlaylistType, mirroring Android's bare-name-relative-to-root
 * model - so those two methods can't just treat their argument as an already-absolute path.
 *
 * The index file is the only copy of a playlist's metadata (comments, videos, soundtrack, styles,
 * credits...): if it stops parsing, Playlist.loadPlaylist silently falls back to a bare JPEG scan
 * and the next save would overwrite it with that stripped version. So this storage never loses an
 * index: writes are atomic (temp file + move, so a crash or power cut can't truncate it), the
 * previous parseable version is kept as [PLAYLIST_INDEX_BACKUP_FILENAME] and read back if the main
 * index is damaged, and an index that can't be parsed is moved aside rather than overwritten.
 */
class DesktopPlaylistStorage(private val rootDir: File) : PlaylistStorage {

    private fun folderFor(playlistDirName: String): File = File(rootDir, playlistDirName)

    private fun indexFile(playlistDirName: String) = File(folderFor(playlistDirName), PLAYLIST_INDEX_FILENAME)

    override fun getPlaylistType(playlistDirName: String): PlaylistType = PlaylistType.EXTERNAL_STORAGE

    override fun getFullFolderPath(playlistDirName: String): String = folderFor(playlistDirName).absolutePath

    override fun indexFileExists(playlistDirName: String): Boolean = indexFile(playlistDirName).exists()

    /**
     * The index content, or - when it no longer parses (damaged by an external tool, a OneDrive
     * sync conflict, ...) - the last good [PLAYLIST_INDEX_BACKUP_FILENAME] if that one does. The
     * damaged index itself is left in place: the next [writeIndexFile] moves it aside. Read
     * failures (e.g. "The cloud file provider is not running") still throw, as before, so that
     * Playlist.loadPlaylist flags the result as Playlist.indexUnreadable.
     */
    override fun readIndexFile(playlistDirName: String): String {
        val f = indexFile(playlistDirName)
        if (!f.exists()) return ""
        val content = f.readText()
        if (parses(content)) return content
        val backup = runCatching { File(f.parentFile, PLAYLIST_INDEX_BACKUP_FILENAME).readText() }.getOrNull()
        return if (backup != null && parses(backup)) backup else content
    }

    override fun writeIndexFile(playlistDirName: String, absoluteFolderPath: String, yamlContent: String) {
        // Deliberately re-derives the folder from rootDir+playlistDirName rather than trusting
        // absoluteFolderPath: Playlist.createFromDisk's disk-scan fallback (synced from Android,
        // where it's harmless - that code path there never reaches save() with it unresolved)
        // returns a Playlist whose absolutePath is just the bare dir name, not a real path.
        // Desktop can always cheaply re-derive the real folder, so it just does that instead.
        val target = indexFile(playlistDirName)
        if (target.exists()) {
            val current = try {
                target.readText()
            } catch (e: IOException) {
                null
            }
            if (current != null && parses(current)) {
                // Plain copy (not atomic) is fine: the backup is only ever a fallback for the main index.
                Files.copy(target.toPath(), File(target.parentFile, PLAYLIST_INDEX_BACKUP_FILENAME).toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                preserveIndexAside(playlistDirName)
            }
        }
        writeAtomically(target, yamlContent)
    }

    /**
     * Moves the current index (if any) to a timestamped `playlistIndex.unreadable-*.yaml` next to it,
     * so a following [writeIndexFile] can't destroy it - for an index that doesn't parse, and for a
     * Playlist.indexUnreadable playlist (see PlaylistEditorState.save), whose index may read fine
     * again by now (OneDrive back online) yet must not be replaced by the stripped disk-scan version
     * without keeping the original. Throws [IOException] if the move fails, so nothing is
     * overwritten in that case.
     */
    fun preserveIndexAside(playlistDirName: String) {
        val source = indexFile(playlistDirName)
        if (!source.exists()) return
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        var dest = File(source.parentFile, "$PLAYLIST_INDEX_UNREADABLE_PREFIX$stamp.yaml")
        var n = 2
        while (dest.exists()) dest = File(source.parentFile, "$PLAYLIST_INDEX_UNREADABLE_PREFIX$stamp-${n++}.yaml")
        Files.move(source.toPath(), dest.toPath())
        System.err.println("Playlist index of '$playlistDirName' could not be read: kept as ${dest.name}")
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.writeText(content)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun parses(yaml: String): Boolean = runCatching { getYamlPlaylistFromYaml(yaml) }.isSuccess

    override fun listJpegs(playlistDirName: String): List<Pair<String, String>> {
        val dir = folderFor(playlistDirName)
        val files = dir.listFiles { f -> f.isFile && (f.extension.equals("jpg", ignoreCase = true) || f.extension.equals("jpeg", ignoreCase = true)) }
            ?: emptyArray()
        // toPath().toUri().toASCIIString(), not File.toURI()/toString() - see the comment on the same
        // call in PlaylistEditorState.addPhotos for why those break coil3 on Windows.
        return files.sortedBy { it.name }.map { it.name to it.toPath().toUri().toASCIIString() }
    }

    override fun resolvePhotoUri(fullPlaylistFolder: String, playlistDirName: String, filename: String, withFileVerification: Boolean): String {
        val f = File(fullPlaylistFolder, filename)
        if (withFileVerification && !f.exists()) {
            throw Exception("Yaml : $filename does not exists")
        }
        // toASCIIString(): see listJpegs.
        return f.toPath().toUri().toASCIIString()
    }

    override fun resolveAbsoluteFolder(fullPlaylistFolder: String, playlistDirName: String, yamlIndexName: String): String =
        fullPlaylistFolder
}

fun playlistItemFile(imageUriString: String): File = File(java.net.URI(imageUriString))
