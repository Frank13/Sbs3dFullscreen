import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Bounded cache of decoded full-size photo bitmaps, shared by ImageScreen (see its `LaunchedEffect
 * (file)`) and Main.kt's Next/Previous neighbor prefetch (see [warm]) - mirrors GalleryScreen.kt's
 * ThumbnailCache, but for the full-resolution image ImageScreen actually displays rather than a
 * small thumbnail, so navigating to an already-prefetched neighbor shows instantly instead of
 * blocking on a fresh disk read + JPEG decode every time.
 *
 * Also the one place that catches a corrupt/truncated/not-actually-an-image file instead of
 * letting the decode exception (or OutOfMemoryError, on a huge file) crash the whole app - this
 * matters more here than in GalleryScreen's thumbnail path because the app can be launched
 * directly on an arbitrary file via Windows' file association, with no chance to route around a
 * bad file first. See [Result.Failed] and ImageScreen's decodeFailed state.
 */
object ImageDecodeCache {
    // Small on purpose: unlike ThumbnailCache's icon-sized bitmaps, these are full-resolution
    // side-by-side 3D photos (easily tens of MB decoded) - only the current photo plus a couple of
    // Next/Previous neighbors need to stay resident at once.
    private const val maxEntries = 5

    // Own SupervisorJob scope (like ThumbnailCache) so a warm() prefetch keeps running even if the
    // navigation that requested it (e.g. a quick double Next) moves on before it finishes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReporting.coroutineHandler)

    sealed interface Result {
        data class Loaded(val bitmap: ImageBitmap) : Result
        data object Failed : Result
    }

    // Extensions ImageDecodeCache actually knows how to decode - matches ImageScreen's own
    // Mpo.isMpoFile/plain-JPEG branching. warm() no-ops on anything else (e.g. a playlist video
    // slide's .mp4) rather than wasting a decode attempt doomed to fail.
    private val decodableExtensions = setOf("jpg", "jpeg", "mpo")

    private data class Key(val path: String, val lastModified: Long)
    private fun keyOf(file: File) = Key(file.path, file.lastModified())

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<Key, Result>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Result>) = size > maxEntries
        },
    )
    private val inFlight = ConcurrentHashMap<Key, Deferred<Result>>()

    /** Cached value if already decoded, without waiting. */
    fun peek(file: File): Result? = cache[keyOf(file)]

    /** Decodes (or joins an in-flight decode of, or returns the cached value for) [file]. */
    suspend fun load(file: File): Result {
        val key = keyOf(file)
        cache[key]?.let { return it }
        val deferred = inFlight.getOrPut(key) { scope.async { decode(file) } }
        return deferred.await().also {
            cache[key] = it
            inFlight.remove(key, deferred)
        }
    }

    /** Fire-and-forget decode of [file] into the cache, for Main.kt's Next/Previous neighbor prefetch. */
    fun warm(file: File) {
        if (file.extension.lowercase() !in decodableExtensions) return
        if (peek(file) != null || inFlight.containsKey(keyOf(file))) return
        scope.launch { load(file) }
    }

    private fun decode(file: File): Result = runCatching {
        // .mpo's two stereo frames are composed purely in memory (see Mpo.kt's doc comment) - just
        // viewing one never writes a converted copy to disk, only an actual edit "Save" does.
        val bitmap = if (Mpo.isMpoFile(file)) {
            Mpo.decodeComposedImage(file)?.toComposeImageBitmap()
        } else {
            file.readBytes().decodeToImageBitmap()
        }
        bitmap?.let { Result.Loaded(it) } ?: Result.Failed
    }.getOrElse { Result.Failed }
}
