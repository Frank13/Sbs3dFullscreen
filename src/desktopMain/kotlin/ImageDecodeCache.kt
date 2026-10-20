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
    // side-by-side 3D photos - only the current photo plus a couple of Next/Previous neighbors need
    // to stay resident at once.
    private const val maxEntries = 5

    // ...and bounded by decoded size too, since a count alone doesn't bound memory: a full-width
    // SBS from a 24 MP camera is ~190 MB as ARGB, so 5 of those could exhaust a smaller heap. A
    // third of the max heap leaves room for the decode in progress (plus its readBytes() buffer)
    // and the rest of the app. The most recent entry is always kept, however large.
    private val maxBytes = Runtime.getRuntime().maxMemory() / 3

    private fun bytesOf(result: Result): Long =
        if (result is Result.Loaded) result.bitmap.width.toLong() * result.bitmap.height * 4 else 0L

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
    private val decodableExtensions = PhotoExtensions

    private data class Key(val path: String, val lastModified: Long)
    private fun keyOf(file: File) = Key(file.path, file.lastModified())

    // Access-ordered (LRU first), guarded by its own monitor together with cachedBytes.
    private val cache = LinkedHashMap<Key, Result>(16, 0.75f, true)
    private var cachedBytes = 0L
    private val inFlight = ConcurrentHashMap<Key, Deferred<Result>>()

    private fun get(key: Key): Result? = synchronized(cache) { cache[key] }

    private fun put(key: Key, result: Result) = synchronized(cache) {
        cache.put(key, result)?.let { cachedBytes -= bytesOf(it) }
        cachedBytes += bytesOf(result)
        val iterator = cache.entries.iterator()
        while (cache.size > 1 && (cache.size > maxEntries || cachedBytes > maxBytes)) {
            val eldest = iterator.next()
            cachedBytes -= bytesOf(eldest.value)
            iterator.remove()
        }
    }

    /** Cached value if already decoded, without waiting. */
    fun peek(file: File): Result? = get(keyOf(file))

    /** Decodes (or joins an in-flight decode of, or returns the cached value for) [file]. */
    suspend fun load(file: File): Result {
        val key = keyOf(file)
        get(key)?.let { return it }
        val deferred = inFlight.getOrPut(key) { scope.async { decode(file) } }
        return deferred.await().also {
            put(key, it)
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
