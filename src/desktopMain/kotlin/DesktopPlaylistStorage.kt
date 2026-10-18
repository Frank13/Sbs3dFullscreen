import fr.camera3d.camera.feature_playlists.domain.PLAYLIST_INDEX_FILENAME
import fr.camera3d.camera.feature_playlists.domain.PlaylistStorage
import fr.camera3d.camera.feature_playlists.domain.PlaylistType
import java.io.File

/**
 * Desktop PlaylistStorage: no SAF/MediaStore concept exists here, so `rootDir` plays the role
 * Android's fixed "Playlists" folder does - `playlistDirName` is a bare folder name resolved
 * against it. This matters because Playlist.kt's own createFromDisk (synced verbatim from
 * Android) always reduces its `fullPlaylistFolder` argument down to a bare last-path-segment
 * name before calling listJpegs/getPlaylistType, mirroring Android's bare-name-relative-to-root
 * model - so those two methods can't just treat their argument as an already-absolute path.
 */
class DesktopPlaylistStorage(private val rootDir: File) : PlaylistStorage {

    private fun folderFor(playlistDirName: String): File = File(rootDir, playlistDirName)

    override fun getPlaylistType(playlistDirName: String): PlaylistType = PlaylistType.EXTERNAL_STORAGE

    override fun getFullFolderPath(playlistDirName: String): String = folderFor(playlistDirName).absolutePath

    override fun indexFileExists(playlistDirName: String): Boolean =
        File(folderFor(playlistDirName), PLAYLIST_INDEX_FILENAME).exists()

    override fun readIndexFile(playlistDirName: String): String {
        val f = File(folderFor(playlistDirName), PLAYLIST_INDEX_FILENAME)
        return if (f.exists()) f.readText() else ""
    }

    override fun writeIndexFile(playlistDirName: String, absoluteFolderPath: String, yamlContent: String) {
        // Deliberately re-derives the folder from rootDir+playlistDirName rather than trusting
        // absoluteFolderPath: Playlist.createFromDisk's disk-scan fallback (synced from Android,
        // where it's harmless - that code path there never reaches save() with it unresolved)
        // returns a Playlist whose absolutePath is just the bare dir name, not a real path.
        // Desktop can always cheaply re-derive the real folder, so it just does that instead.
        File(folderFor(playlistDirName), PLAYLIST_INDEX_FILENAME).writeText(yamlContent)
    }

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
