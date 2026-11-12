import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.Executors

/**
 * Minimal direct binding to libVLC 3.x through JNA, replacing the vlcj library (GPLv3) this app
 * used before: only the handful of calls VideoScreen.kt/VideoThumbnail.kt/AudioDevices.kt need.
 * libVLC itself (LGPL) is not bundled - it's loaded from the VLC the user has installed (see
 * [Vlc.library] for where it looks), so video playback requires VLC on the machine.
 */

/** One entry of libVLC's audio device list; an empty [deviceId] is libVLC's own "default" entry. */
data class AudioDevice(val deviceId: String, val longName: String)

internal interface LibVlc : Library {
    fun libvlc_new(argc: Int, argv: Array<String>?): Pointer?
    fun libvlc_release(instance: Pointer)

    fun libvlc_media_new_path(instance: Pointer, path: String): Pointer?
    fun libvlc_media_release(media: Pointer)
    fun libvlc_media_add_option(media: Pointer, option: String)

    fun libvlc_media_player_new(instance: Pointer): Pointer?
    fun libvlc_media_player_release(player: Pointer)
    fun libvlc_media_player_set_media(player: Pointer, media: Pointer)
    fun libvlc_media_player_play(player: Pointer): Int
    fun libvlc_media_player_stop(player: Pointer)
    fun libvlc_media_player_set_pause(player: Pointer, doPause: Int)
    fun libvlc_media_player_set_position(player: Pointer, position: Float)
    fun libvlc_media_player_event_manager(player: Pointer): Pointer

    fun libvlc_video_set_callbacks(player: Pointer, lock: VlcLockCallback, unlock: VlcUnlockCallback?, display: VlcDisplayCallback?, opaque: Pointer?)
    fun libvlc_video_set_format_callbacks(player: Pointer, setup: VlcFormatCallback, cleanup: VlcCleanupCallback?)

    fun libvlc_audio_set_mute(player: Pointer, status: Int)
    fun libvlc_audio_set_volume(player: Pointer, volume: Int): Int
    fun libvlc_audio_output_device_set(player: Pointer, module: String?, deviceId: String?)
    fun libvlc_audio_output_device_list_get(instance: Pointer, aout: String): Pointer?
    fun libvlc_audio_output_device_list_release(list: Pointer)

    fun libvlc_event_attach(manager: Pointer, eventType: Int, callback: VlcEventCallback, userData: Pointer?): Int
}

internal interface VlcLockCallback : Callback { fun invoke(opaque: Pointer?, planes: Pointer): Pointer? }
internal interface VlcUnlockCallback : Callback { fun invoke(opaque: Pointer?, picture: Pointer?, planes: Pointer?) }
internal interface VlcDisplayCallback : Callback { fun invoke(opaque: Pointer?, picture: Pointer?) }
internal interface VlcCleanupCallback : Callback { fun invoke(opaque: Pointer?) }
internal interface VlcEventCallback : Callback { fun invoke(event: Pointer, userData: Pointer?) }
internal interface VlcFormatCallback : Callback {
    fun invoke(opaque: Pointer?, chroma: Pointer, width: Pointer, height: Pointer, pitches: Pointer, lines: Pointer): Int
}

// libvlc_event_e values (libVLC 3.x) for the events this app listens to.
private const val EventMediaPlayerEndReached = 265
private const val EventMediaPlayerEncounteredError = 266
private const val EventMediaPlayerPositionChanged = 268

// libvlc_event_t is { int type; void *p_obj; union u } - on 64-bit the union (whose first member for
// a position-changed event is the float new_position) starts at offset 16.
private const val EventUnionOffset = 16L

object Vlc {
    private val library: LibVlc by lazy { loadLibrary() }

    // libVLC forbids calling back into the player from inside one of its own event callbacks
    // (deadlock), so anything triggered from an event (repeat) is bounced onto this thread.
    internal val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vlc-worker").apply { isDaemon = true } }

    private fun loadLibrary(): LibVlc {
        val dir = findVlcDirectory() ?: error("VLC is not installed (libvlc.dll not found)")
        // libvlc.dll depends on libvlccore.dll in the same folder: load that one first so the
        // dependency resolves to the already-loaded module whatever the process search path is.
        System.load(File(dir, "libvlccore.dll").absolutePath)
        return Native.load(
            File(dir, "libvlc.dll").absolutePath,
            LibVlc::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"),
        )
    }

    private fun findVlcDirectory(): File? {
        val candidates = listOfNotNull(
            System.getProperty("vlc.dir"),
            System.getenv("ProgramW6432")?.let { "$it\\VideoLAN\\VLC" },
            System.getenv("ProgramFiles")?.let { "$it\\VideoLAN\\VLC" },
            "C:\\Program Files\\VideoLAN\\VLC",
            registryInstallDir(),
        )
        return candidates.map(::File).firstOrNull { File(it, "libvlc.dll").isFile && File(it, "libvlccore.dll").isFile }
    }

    /** VLC's installer records its folder here, which covers non-default install locations. */
    private fun registryInstallDir(): String? = runCatching {
        val process = ProcessBuilder("reg", "query", "HKLM\\SOFTWARE\\VideoLAN\\VLC", "/v", "InstallDir")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        Regex("InstallDir\\s+REG_SZ\\s+(.+)").find(output)?.groupValues?.get(1)?.trim()
    }.getOrNull()

    internal fun lib(): LibVlc = library

    const val DownloadUrl = "https://www.videolan.org/vlc/download-windows.html"

    /**
     * True when a VLC install with the libVLC DLLs is present. Only looks at the disk (nothing is
     * loaded) and isn't cached, so it picks up a VLC the user installs while the app is running.
     */
    fun isInstalled(): Boolean = findVlcDirectory() != null
}

/** A libVLC instance (what vlcj called a MediaPlayerFactory). Throws if VLC isn't installed. */
class VlcInstance(vararg args: String) {
    private val lib = Vlc.lib()
    private val handle: Pointer = lib.libvlc_new(args.size, arrayOf(*args)) ?: error("libvlc_new failed")

    /** The Windows (mmdevice) audio devices, including libVLC's own "default" entry. */
    fun audioDevices(): List<AudioDevice> {
        val head = lib.libvlc_audio_output_device_list_get(handle, "mmdevice") ?: return emptyList()
        try {
            val devices = mutableListOf<AudioDevice>()
            var node: Pointer? = head
            // libvlc_audio_output_device_t: { next; psz_device; psz_description }
            while (node != null) {
                val id = node.getPointer(8)?.getString(0, "UTF-8") ?: ""
                val name = node.getPointer(16)?.getString(0, "UTF-8") ?: id
                devices += AudioDevice(id, name)
                node = node.getPointer(0)
            }
            return devices
        } finally {
            lib.libvlc_audio_output_device_list_release(head)
        }
    }

    fun newPlayer(): VlcPlayer {
        val player = lib.libvlc_media_player_new(handle) ?: error("libvlc_media_player_new failed")
        return VlcPlayer(lib, handle, player)
    }

    fun release() = lib.libvlc_release(handle)
}

class VlcPlayer internal constructor(private val lib: LibVlc, private val instance: Pointer, private val handle: Pointer) {
    @Volatile private var repeat = false
    @Volatile private var released = false

    /** Fraction [0,1] of playback elapsed; called on a libVLC thread. */
    var onPositionChanged: ((Float) -> Unit)? = null

    /** Called on a libVLC thread when playback reaches the end (even when repeating). */
    var onFinished: (() -> Unit)? = null

    /**
     * Called on a libVLC thread when the file can't be played: libVLC reports an error, or playback
     * "ends" before a single frame was displayed (a 0-byte/corrupt file, or an online-only OneDrive
     * file while OneDrive isn't running). Never followed by a repeat, which would only restart the
     * same failure in an endless loop.
     */
    var onError: (() -> Unit)? = null

    // Set by setFrameListener's display callback - see onError.
    @Volatile private var hasDisplayedFrame = false

    // JNA only keeps a weak reference to callbacks: hold them here so they aren't collected while
    // libVLC may still call them.
    private var lockCallback: VlcLockCallback? = null
    private var unlockCallback: VlcUnlockCallback? = null
    private var displayCallback: VlcDisplayCallback? = null
    private var formatCallback: VlcFormatCallback? = null
    private var cleanupCallback: VlcCleanupCallback? = null
    private val eventCallback = object : VlcEventCallback {
        override fun invoke(event: Pointer, userData: Pointer?) {
            when (event.getInt(0)) {
                EventMediaPlayerPositionChanged -> onPositionChanged?.invoke(event.getFloat(EventUnionOffset))
                EventMediaPlayerEncounteredError -> onError?.invoke()
                EventMediaPlayerEndReached -> {
                    if (!hasDisplayedFrame) {
                        onError?.invoke()
                        return
                    }
                    onFinished?.invoke()
                    if (repeat) Vlc.worker.execute {
                        if (!released) {
                            lib.libvlc_media_player_stop(handle)
                            lib.libvlc_media_player_play(handle)
                        }
                    }
                }
            }
        }
    }

    init {
        val manager = lib.libvlc_media_player_event_manager(handle)
        lib.libvlc_event_attach(manager, EventMediaPlayerPositionChanged, eventCallback, null)
        lib.libvlc_event_attach(manager, EventMediaPlayerEndReached, eventCallback, null)
        lib.libvlc_event_attach(manager, EventMediaPlayerEncounteredError, eventCallback, null)
    }

    /**
     * Renders video headlessly (no native window): each decoded frame is copied into a
     * [BufferedImage] ([BufferedImage.TYPE_INT_RGB], sized to the video) handed to [onFrame] on a
     * libVLC thread. The same image instance is reused for every frame, so convert/copy it
     * inside [onFrame]. Must be called before [play].
     */
    fun setFrameListener(onFrame: (BufferedImage) -> Unit) {
        var image: BufferedImage? = null
        var buffer: Memory? = null

        val format = object : VlcFormatCallback {
            override fun invoke(opaque: Pointer?, chroma: Pointer, width: Pointer, height: Pointer, pitches: Pointer, lines: Pointer): Int {
                val w = width.getInt(0)
                val h = height.getInt(0)
                // "RV32" = 32-bit BGRX in memory, i.e. a little-endian 0x00RRGGBB int per pixel.
                chroma.write(0, "RV32".toByteArray(Charsets.US_ASCII), 0, 4)
                pitches.setInt(0, w * 4)
                lines.setInt(0, h)
                image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
                buffer = Memory(w.toLong() * h * 4)
                return 1
            }
        }
        val lock = object : VlcLockCallback {
            override fun invoke(opaque: Pointer?, planes: Pointer): Pointer? {
                planes.setPointer(0, buffer)
                return null
            }
        }
        val unlock = object : VlcUnlockCallback {
            override fun invoke(opaque: Pointer?, picture: Pointer?, planes: Pointer?) {}
        }
        val display = object : VlcDisplayCallback {
            override fun invoke(opaque: Pointer?, picture: Pointer?) {
                val currentImage = image ?: return
                val currentBuffer = buffer ?: return
                val pixels = (currentImage.raster.dataBuffer as DataBufferInt).data
                currentBuffer.read(0, pixels, 0, pixels.size)
                hasDisplayedFrame = true
                onFrame(currentImage)
            }
        }
        val cleanup = object : VlcCleanupCallback {
            override fun invoke(opaque: Pointer?) {}
        }
        formatCallback = format
        lockCallback = lock
        unlockCallback = unlock
        displayCallback = display
        cleanupCallback = cleanup
        lib.libvlc_video_set_callbacks(handle, lock, unlock, display, null)
        lib.libvlc_video_set_format_callbacks(handle, format, cleanup)
    }

    fun setRepeat(repeat: Boolean) {
        this.repeat = repeat
    }

    fun setMute(mute: Boolean) = lib.libvlc_audio_set_mute(handle, if (mute) 1 else 0)

    /** libVLC's own software volume for this player, 0..100 (100 = unattenuated). */
    fun setVolume(percent: Int) { lib.libvlc_audio_set_volume(handle, percent.coerceIn(0, 100)) }

    /**
     * Sends audio to [deviceId] ("" = libVLC's default). With a [module] name (before [play]) it is
     * stored as that module's device variable; with a null module it applies to the live audio output.
     */
    fun setOutputDevice(module: String?, deviceId: String) = lib.libvlc_audio_output_device_set(handle, module, deviceId)

    /**
     * With [noAudio] the audio track is never decoded nor sent to an output. Prefer this to [setMute]
     * for a permanently silent video: on Windows libVLC's mute acts on the process-wide audio session,
     * so it would also silence every other player of this process (e.g. the playlist soundtrack).
     */
    fun play(path: String, noAudio: Boolean = false) {
        val media = lib.libvlc_media_new_path(instance, path) ?: error("libvlc_media_new_path failed")
        if (noAudio) lib.libvlc_media_add_option(media, ":no-audio")
        lib.libvlc_media_player_set_media(handle, media)
        // The player holds its own reference to the media now.
        lib.libvlc_media_release(media)
        lib.libvlc_media_player_play(handle)
    }

    fun setPause(pause: Boolean) = lib.libvlc_media_player_set_pause(handle, if (pause) 1 else 0)

    fun setPosition(fraction: Float) = lib.libvlc_media_player_set_position(handle, fraction)

    fun stop() = lib.libvlc_media_player_stop(handle)

    fun release() {
        released = true
        lib.libvlc_media_player_stop(handle)
        lib.libvlc_media_player_release(handle)
    }
}
