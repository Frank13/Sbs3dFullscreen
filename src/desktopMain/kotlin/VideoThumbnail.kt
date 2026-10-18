import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.feature_playlists.domain.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// How many decoded frames to skip before capturing one for a thumbnail - the first one or two
// frames of an mp4 are frequently solid black, which would make every video thumbnail blank.
private const val FramesToSkip = 3
private const val ExtractionTimeoutSeconds = 5L

/**
 * Real (not placeholder) video thumbnails, decoded via the same real-libVLC engine
 * VideoScreen.kt uses for live playback, but as a short-lived, one-shot, muted capture of a
 * single frame instead of a live decode loop. Kept as a small in-memory, session-only cache
 * (mirrors GalleryScreen.kt's ThumbnailCache shape) - no disk persistence, since playlists are
 * modest in size and re-extracting after an app restart is cheap enough.
 */
private object VideoThumbnailCache {
    private const val maxEntries = 200

    // Own SupervisorJob scope (like ThumbnailCache) so an in-flight extraction isn't cancelled if
    // the composable that requested it briefly leaves composition (e.g. scrolled out of view).
    // Parallelism capped at 1: each extraction spins up its own native libVLC instance,
    // and running more than one concurrently (e.g. several video items visible at once in a
    // playlist) causes libVLC vout/decoder resource contention - "Failed to set on top" and h264
    // "get_buffer() failed"/"decode_slice_header error" - so extractions are serialized instead.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + CrashReporting.coroutineHandler)

    sealed class Result {
        data class Loaded(val bitmap: ImageBitmap) : Result()
        object Failed : Result()
    }

    private data class Key(val path: String, val lastModified: Long)
    private fun keyOf(file: File) = Key(file.path, file.lastModified())

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<Key, Result>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Result>) = size > maxEntries
        },
    )
    private val inFlight = ConcurrentHashMap<Key, Deferred<Result>>()

    /** Cached value if already extracted (successfully or not), without waiting - null means "not attempted yet". */
    fun peek(file: File): Result? = cache[keyOf(file)]

    suspend fun load(file: File): Result {
        val key = keyOf(file)
        cache[key]?.let { return it }
        val deferred = inFlight.getOrPut(key) { scope.async { extract(file) } }
        return deferred.await().also {
            cache[key] = it
            inFlight.remove(key, deferred)
        }
    }

    private fun extract(file: File): Result =
        runCatching { extractFrameBlocking(file) }.getOrNull()?.let { Result.Loaded(it) } ?: Result.Failed

    /**
     * Blocks the calling (IO-dispatcher) thread until a frame is captured or [ExtractionTimeoutSeconds]
     * elapses. Reuses the same frame-listener wiring as VideoScreen.kt's live playback, muted, stopped and released as soon as a frame lands
     * (or the timeout fires) rather than kept alive for continuous playback.
     */
    private fun extractFrameBlocking(file: File): ImageBitmap? {
        // --quiet: this factory's player is stopped within a few frames of starting (as soon as
        // FramesToSkip is reached), which routinely races libVLC's threaded h264 decoder shutdown
        // and floods stderr with harmless "get_buffer() failed"/"decode_slice_header error"/"no
        // frame!" teardown noise - silence libVLC's own native logging here since the only outcome
        // that matters for a one-shot thumbnail grab is the captured bitmap (or Result.Failed).
        val factory = VlcInstance("--quiet")
        val player = factory.newPlayer()
        try {
            var frameCount = 0
            var result: ImageBitmap? = null
            val latch = CountDownLatch(1)

            player.setFrameListener { image ->
                frameCount++
                if (frameCount >= FramesToSkip && latch.count != 0L) {
                    result = image.toComposeImageBitmap()
                    latch.countDown()
                }
            }
            player.setMute(true)
            player.play(file.absolutePath)
            latch.await(ExtractionTimeoutSeconds, TimeUnit.SECONDS)
            return result
        } finally {
            player.stop()
            player.release()
            factory.release()
        }
    }
}

@Composable
private fun rememberVideoThumbnail(file: File): VideoThumbnailCache.Result? {
    val result by produceState(initialValue = VideoThumbnailCache.peek(file), key1 = file) {
        value = VideoThumbnailCache.load(file)
    }
    return result
}

/** Single big video thumbnail for the Playlist Edit screen - same loading/error-slot shape as ComposableItemPhoto. */
@Composable
fun ComposableVideoThumbnail(file: File, modifier: Modifier = Modifier) {
    val result = rememberVideoThumbnail(file)
    Box(modifier, contentAlignment = Alignment.Center) {
        when (result) {
            is VideoThumbnailCache.Result.Loaded -> Image(
                bitmap = result.bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            VideoThumbnailCache.Result.Failed -> Icon(Icons.Filled.BrokenImage, contentDescription = null)
            null -> CircularProgressIndicator()
        }
    }
}

/**
 * PlaylistScreen's video counterpart to the shared PlaylistItemRow.kt's ComposablePlaylistItem -
 * same signature/layout (Surface/Row/thumbnail-Box/comment-Text), so the caller can swap between
 * them per-item with no visual seam, but backed by [ComposableVideoThumbnail]'s real extracted
 * frame instead of coil3.compose.SubcomposeAsyncImage, which has no video decoder on desktop/JVM
 * (unlike Android's coil3-video module) and would otherwise always show the broken-image icon.
 * Deliberately not merged into the shared file (which must stay free of desktop-only libVLC code) -
 * mirroring its layout here instead, kept small enough that drift is easy to notice/fix by hand.
 */
@Composable
fun ComposableVideoPlaylistItem(
    index: Int = 0,
    photo: PlaylistItem,
    shadowElevation: Dp = 4.dp,
    onOpenPlaylistItem: (Int) -> Unit = {},
    reorderModifier: Modifier = Modifier,
    halfWidthImage: Boolean = false,
    imageHeight: Dp = 100.dp,
    imageWidth: Dp = 200.dp,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().then(reorderModifier),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = shadowElevation,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp).clickable { onOpenPlaylistItem(index) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val imageModifier = if (halfWidthImage) {
                Modifier.weight(1f).height(imageHeight)
            } else {
                Modifier.size(imageWidth, imageHeight)
            }
            Box(imageModifier, contentAlignment = Alignment.Center) {
                ComposableVideoThumbnail(file = playlistItemFile(photo.imageUriString), modifier = Modifier.fillMaxSize())
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                photo.comment,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
