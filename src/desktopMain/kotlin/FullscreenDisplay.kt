import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPosition
import java.io.File
import java.security.MessageDigest

/**
 * The display the fullscreen SBS viewer should open on, remembered per exact set of connected
 * screens ([ScreenWatcher.screenSetKey]): the stored value is the chosen screen's
 * [ConnectedScreen.id] - absolute, so "this screen" stays the same physical screen wherever the
 * app window happens to be next time.
 */
object FullscreenDisplayPreference {
    // Preferences keys are limited to 80 chars and the device paths are much longer: hashed.
    private fun keyFor(screenSetKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(screenSetKey.toByteArray(Charsets.UTF_8))
        return "fullscreenDisplay." + digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun load(screenSetKey: String): String? = DynamicStringPrefs.load(keyFor(screenSetKey))
    fun save(screenSetKey: String, screenId: String) = DynamicStringPrefs.save(keyFor(screenSetKey), screenId)
    fun clear(screenSetKey: String) = DynamicStringPrefs.remove(keyFor(screenSetKey))
}

/** What to do when the fullscreen viewer is about to open - see [FullscreenDisplayState.decide]. */
sealed interface DisplayDecision {
    /** Ask the user with FullscreenDisplayDialog. */
    data object Ask : DisplayDecision

    /** Open on [target], or where the window already is when null. */
    data class Use(val target: ConnectedScreen?) : DisplayDecision
}

/**
 * Compose-observable view of [FullscreenDisplayPreference] for the current screen set, kept by
 * [AppViewModel] (so it survives Window recreations) next to the [ScreenWatcher] it reads.
 */
class FullscreenDisplayState(private val watcher: ScreenWatcher) {
    // Plain (non-state) cache, so reading it during composition never writes Compose state; readers
    // re-read through [version] (bumped by setRemembered) and watcher.screens (via screenSetKey).
    private var version by mutableStateOf(0)
    private var cacheKey: String? = null
    private var cacheValue: String? = null

    /** The remembered screen id for the current screen set, null for "Ask each time". */
    val rememberedScreenId: String?
        get() {
            @Suppress("UNUSED_VARIABLE") val subscribe = version
            val key = watcher.screenSetKey
            if (key != cacheKey) {
                // A registry read, only once per screen set.
                cacheValue = FullscreenDisplayPreference.load(key)
                cacheKey = key
            }
            return cacheValue
        }

    /** Remembers [screenId] for the current screen set, or forgets it ("Ask each time") when null. */
    fun setRemembered(screenId: String?) {
        val key = watcher.screenSetKey
        if (screenId == null) FullscreenDisplayPreference.clear(key) else FullscreenDisplayPreference.save(key, screenId)
        cacheKey = key
        cacheValue = screenId
        version++
    }

    fun decide(): DisplayDecision {
        if (!watcher.needsDisplayChoice) return DisplayDecision.Use(null)
        val remembered = rememberedScreenId ?: return DisplayDecision.Ask
        val screen = watcher.distinctDesktopScreens.firstOrNull { it.id == remembered }
            // Same screen set but that id no longer a distinct desktop (e.g. now duplicated): ask again.
            ?: return DisplayDecision.Ask
        return DisplayDecision.Use(screen)
    }
}

/** Where the 2D remote control window opens: the app's position/size from just before fullscreen. */
data class RemoteControlBounds(val position: WindowPosition, val size: DpSize)

private val DefaultWindowSize = DpSize(800.dp, 600.dp)

/**
 * A window position + size (Compose window dp = AWT user-space units) that puts a [size] window
 * in the middle of [bounds] (an [ScreenManager.awtBoundsOf] result), shrunk to fit if needed.
 */
fun centeredIn(bounds: java.awt.Rectangle, size: DpSize): Pair<WindowPosition, DpSize> {
    val requested = if (size.isSpecified) size else DefaultWindowSize
    val w = minOf(requested.width.value, bounds.width * 0.9f)
    val h = minOf(requested.height.value, bounds.height * 0.9f)
    val position = WindowPosition((bounds.x + (bounds.width - w) / 2f).dp, (bounds.y + (bounds.height - h) / 2f).dp)
    return position to DpSize(w.dp, h.dp)
}

/**
 * How a launch with [LaunchPlan] starts - decided in Main.kt's runApp before the Window exists,
 * since a double-clicked file (file association) opens straight in the fullscreen viewer.
 * [openViewerNow]: open the file in the viewer at once (as before this feature); false with
 * [askDisplay] when FullscreenDisplayDialog must ask first, from the Welcome screen.
 * [viewerPosition] (with [restorePosition]/[remoteControlBounds]): the remembered screen isn't the
 * primary one new windows open on, so the viewer window is created on it directly.
 */
class LaunchPlan(
    val watcher: ScreenWatcher,
    val openViewerNow: Boolean,
    val askDisplay: Boolean = false,
    val viewerPosition: WindowPosition? = null,
    val restorePosition: WindowPosition? = null,
    val remoteControlBounds: RemoteControlBounds? = null,
)

/** See [LaunchPlan]. Seeds the returned watcher with one synchronous screen enumeration. */
fun planLaunch(initialFile: File?): LaunchPlan {
    val watcher = ScreenWatcher()
    if (initialFile == null) return LaunchPlan(watcher, openViewerNow = false)
    val screens = ScreenManager.listScreens()
    if (screens.isNotEmpty()) watcher.apply(ScreenWatcher.PollResult(screens, ScreenManager.screenMode(screens), currentGdiDeviceName = null))
    return when (val decision = FullscreenDisplayState(watcher).decide()) {
        DisplayDecision.Ask -> LaunchPlan(watcher, openViewerNow = false, askDisplay = true)
        is DisplayDecision.Use -> {
            // New windows open on the primary screen: only another target needs moving.
            val bounds = decision.target?.takeIf { !it.isPrimary }?.let(ScreenManager::awtBoundsOf)
            if (bounds == null) {
                LaunchPlan(watcher, openViewerNow = true)
            } else {
                val restore = watcher.screens.firstOrNull { it.isPrimary }
                    ?.let(ScreenManager::awtBoundsOf)
                    ?.let { centeredIn(it, DefaultWindowSize).first }
                LaunchPlan(
                    watcher,
                    openViewerNow = true,
                    viewerPosition = centeredIn(bounds, DefaultWindowSize).first,
                    restorePosition = restore,
                    remoteControlBounds = RemoteControlBounds(restore ?: WindowPosition.PlatformDefault, DefaultWindowSize),
                )
            }
        }
    }
}
