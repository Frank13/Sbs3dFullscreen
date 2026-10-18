package fr.camera3d.camera.feature_playlists.domain

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

import fr.camera3d.camera.feature_playlists.service.yamlImporter.PlaylistToYAML
import fr.camera3d.camera.feature_playlists.service.yamlImporter.getYamlPlaylistFromYaml
import fr.camera3d.camera.feature_playlists.service.yamlImporter.toFullscreenScroll
import fr.camera3d.camera.feature_playlists.service.yamlImporter.toTextStyleConfig
import java.io.File

const val PLAYLIST_INDEX_FILENAME = "playlistIndex.yaml"

/**
 * true for filenames recognized as SBS video. Like photos, video supports both the full-width
 * and half-width/squeezed SBS conventions via isHalfWidth.
 */
fun isVideoFilename(filename: String): Boolean = filename.endsWith(".mp4", ignoreCase = true)

enum class PlaylistType {EXTERNAL_STORAGE, SAF_TREE_URI_ON_PLAYLISTS, SAF_TREE_URI_ON_SUBFOLDER}

data class Playlist(
    //val index: Int, // index in the list of playlists
    val name : String,
    val type : PlaylistType = PlaylistType.EXTERNAL_STORAGE,
    val absolutePath : String, // content: or file:
    val defaultDurationS : Long = 10,
    val defaultTransition : String = "",
    val soundFilename : String = "",
    val subtitle : String = "", // subtitle shown under the title on the title slide
    val titleZPercent : Float = 0f, // depth shift (stereo "altitude") of the title on the title slide
    val subtitleZPercent : Float = 0f, // depth shift (stereo "altitude") of the subtitle on the title slide
    val titleStyle : TextStyleConfig = TextStyleConfig.TITLE_DEFAULT, // font/color/position style of the title on the title slide
    val subtitleStyle : TextStyleConfig = TextStyleConfig.SUBTITLE_DEFAULT, // font/color/position style of the subtitle on the title slide
    val isAutomated : Boolean = true, // true: slideshow auto-advances after defaultDurationS; false: user advances slides manually
    val scrollSpeedLimitPercent : Float = 5f, // max scrolling speed of "force fullscreen by scrolling" photos, in % of the screen per second (<= 0: no limit); extends their slide duration if needed
    val soundtrack : List<String> = listOf(), // mp3 filenames (in the playlist folder) played one after the other during the slideshow
    var photos : List<PlaylistItem> = listOf() // list of playlists
){

    fun toYAML() : String{
        return PlaylistToYAML(this)
    }

    /**
     * gets the playlist directory name (not fullDirectory with path)
     */
    fun getDirName(): String{
        val f = File(absolutePath)
        return if (f.name.startsWith("primary")){ // Tree Uri
            f.name.split("%2F").last().replace(" ", "")
        } else {// SAF uri
            f.name.replace(" ", "")
        }
    }

    fun getNameCapitalized(): String{
        return name.replaceFirstChar { a -> a.uppercase() }
    }

    fun getRenamedPlaylist(newName : String, newAbsolutePath : String): Playlist {
        return this.copy(name=newName, absolutePath = newAbsolutePath)
    }

    /**
     * Saves the playlist in an index file
     */
    fun save(storage: PlaylistStorage){
        storage.writeIndexFile(getDirName(), absolutePath, toYAML())
    }

    /**
     * returns the list of new photo filenames to add
     */
    private fun getAdditionalPhotosFromDisk(storage: PlaylistStorage) : List<Pair<String, String>> {
        val plist = photos.map { it2 -> it2.filename }
        val playlistDirName = getDirName()
        return storage.listJpegs(playlistDirName).filter { it.first !in  plist}
    }

    /**
     * adds the extra photos at the end of the playlist
     */
    fun addExtraPhotosFromDisk(storage: PlaylistStorage): Playlist {
        val newPhotos = getAdditionalPhotosFromDisk(storage)
        photos += newPhotos.map {
            PlaylistItem(it.first, it.second, isHalfWidth = isVideoFilename(it.first), isVideo = isVideoFilename(it.first))
        }
        save(storage)
        return copy(photos = photos)
    }

    /**
     * returns true if there are new photos to add
     */
    fun haveExtraPhotos(storage: PlaylistStorage) : Int {
        return getAdditionalPhotosFromDisk(storage).size
    }

    companion object Factory{

        /**
         * class method to create a PlaylistState from a YamlPlaylistV1 object
         * it is a constructor-like that allows data manipulation before constructing the object
         * works both for standard files and tree uris
         * @param storage : platform storage primitives (SAF-vs-plain dispatch on Android, plain File on Desktop)
         * @param fullPlaylistFolder : the absolute path of the playlist folder (not ending by /)
         * @param yamlIndexName : the name of the index file e.g. playlistIndex.yaml
         */
        fun createFromYamlPlaylist(storage: PlaylistStorage, fullPlaylistFolder : String, yamlIndexName : String, withFileVerification : Boolean = true, buildUris: Boolean = true, type : PlaylistType) : Playlist{
            // open and read the YAML index file
            var playlistFolder = fullPlaylistFolder.split(File.separator).last()
            playlistFolder = playlistFolder.split("%2F").last()
            val yamlContent = storage.readIndexFile(playlistFolder)
            val p = getYamlPlaylistFromYaml(yamlContent)
            // casting the playlist items
            val items = if (!buildUris) {
                // Shallow load: placeholder uris, no file system access at all
                p.photos.map {
                    PlaylistItem(it.filename, "", it.comment, it.commentAnchor, it.commentZ, it.commentColor, it.transition, it.duration, it.isHalfWidth, it.isVideo, it.isMuted, it.fullscreenScroll.toFullscreenScroll())
                }
            } else {
                p.photos.mapNotNull {
                    try {
                        val uri = storage.resolvePhotoUri(fullPlaylistFolder, playlistFolder, it.filename, withFileVerification)
                        PlaylistItem(it.filename, uri, it.comment, it.commentAnchor, it.commentZ, it.commentColor, it.transition, it.duration, it.isHalfWidth, it.isVideo, it.isMuted, it.fullscreenScroll.toFullscreenScroll())
                    } catch (e: Exception){
                        null
                    }
                }
            }
            val absoluteFolder = storage.resolveAbsoluteFolder(fullPlaylistFolder, playlistFolder, yamlIndexName)
            return Playlist(p.name, type, absoluteFolder, p.defaultDuration, p.defaultTransition, p.soundFilename, p.subtitle, p.titleZ, p.subtitleZ,
                titleStyle = p.titleStyle.toTextStyleConfig(TextStyleConfig.TITLE_DEFAULT),
                subtitleStyle = p.subtitleStyle.toTextStyleConfig(TextStyleConfig.SUBTITLE_DEFAULT),
                isAutomated = p.isAutomated, scrollSpeedLimitPercent = p.scrollSpeedLimitPercent, soundtrack = p.soundtrack, photos = items)
        }

        /**
         * Fast, shallow load of a playlist: parses the YAML index for name and photo count
         * but skips all file existence checks and Uri construction.
         * For EXTERNAL_STORAGE playlists, real file uris are still built (free string operation).
         * For SAF playlists, photos get "" as placeholder.
         * Call [loadPlaylist] afterwards (e.g. when the user opens the playlist) to get real uris.
         */
        fun loadPlaylistShallow(storage: PlaylistStorage, playlistDirName: String): Playlist {
            val type = storage.getPlaylistType(playlistDirName)
            val fullPlaylistFolder = storage.getFullFolderPath(playlistDirName)
            if (storage.indexFileExists(playlistDirName)) {
                return try {
                    // For standard storage: skip file existence check, but uri construction is free.
                    // For SAF: skip uri construction entirely (DocumentFile calls are expensive).
                    val buildUris = (type == PlaylistType.EXTERNAL_STORAGE)
                    createFromYamlPlaylist(
                        storage, fullPlaylistFolder, PLAYLIST_INDEX_FILENAME,
                        withFileVerification = false, buildUris = buildUris, type = type
                    )
                } catch (e: Exception) {
                    createFromDisk(storage, fullPlaylistFolder)
                }
            }
            return createFromDisk(storage, fullPlaylistFolder)
        }

        /**
         * class method to load a playlist in a PlaylistState
         * works both for standard files and tree uris
         */
        fun loadPlaylist(storage: PlaylistStorage, playlistDirName: String): Playlist {
            val type = storage.getPlaylistType(playlistDirName)
            val fullPlaylistFolder = storage.getFullFolderPath(playlistDirName)
            if (storage.indexFileExists(playlistDirName)) {
                // if exists load from index file
                return try {
                    createFromYamlPlaylist(
                        storage,
                        fullPlaylistFolder,
                        PLAYLIST_INDEX_FILENAME,
                        type=type
                    )
                } catch (e:Exception){
                    createFromDisk(storage, fullPlaylistFolder)
                }
            }
            return createFromDisk(storage, fullPlaylistFolder)
        }

        fun canLoadPlaylistAuto(storage: PlaylistStorage, playlistDirName: String): Boolean {
            val type = storage.getPlaylistType(playlistDirName)
            val fullPlaylistFolder = storage.getFullFolderPath(playlistDirName)
            if (!storage.indexFileExists(playlistDirName)) return false
            return try {
                createFromYamlPlaylist(storage, fullPlaylistFolder, PLAYLIST_INDEX_FILENAME, type = type)
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * class method to create a PlaylistState from the content of the playlist folder
         * it is a constructor-like that allows data manipulation before constructing the object
         */
        fun createFromDisk(storage: PlaylistStorage, fullPlaylistFolder : String): Playlist{
            val playlistName = fullPlaylistFolder.substringAfterLast(File.separator)
            val type = storage.getPlaylistType(playlistName)
            // get photo filenames
            val photoNames = storage.listJpegs(playlistName)
            // create PlaylistItems
            val playlistItems = photoNames.map {PlaylistItem(it.first, it.second, isHalfWidth = isVideoFilename(it.first), isVideo = isVideoFilename(it.first))}
            // create PlaylistState
            return Playlist(playlistName, type, playlistName, photos= playlistItems)
        }

        /**
         * class method to end renaming a playlist : updates the playlist index after its directory has been renamed
         * @param storage : platform storage primitives
         * @param playlistNewDirName : the new directory where is located the renamed playlist
         * @param newName : the new playlist name to put in the index
         */
        fun updatePlaylistName(storage: PlaylistStorage, playlistNewDirName : String, newName : String){
            val p= loadPlaylist(storage, playlistNewDirName)
            val pRenamed = p.copy(name=newName)
            pRenamed.save(storage)
        }
    }
}
