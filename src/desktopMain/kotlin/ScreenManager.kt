import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * One monitor currently attached to the desktop, as Windows' display configuration
 * (QueryDisplayConfig) reports it.
 *
 * [id] is the monitor's device interface path - stable across polls and reboots for the same
 * physical monitor on the same port, so it's what [ScreenWatcher] diffs to spot a newly plugged
 * screen. [gdiDeviceName] (`\\.\DISPLAY1`, ...) is the desktop source it shows; two monitors in
 * "duplicate" mode share one. [modelName] is the EDID name the monitor itself reports (e.g.
 * "Odyssey G70NC"), null when it doesn't report one (typical for laptop panels). [brand] is
 * decoded from the EDID's 3-letter PNP manufacturer code ([manufacturerCode], e.g. "SAM").
 * [widthPx]/[heightPx] are the desktop resolution in physical pixels (not DPI-scaled).
 */
data class ConnectedScreen(
    val id: String,
    val gdiDeviceName: String,
    val modelName: String?,
    val manufacturerCode: String?,
    val brand: String?,
    val widthPx: Int,
    val heightPx: Int,
    val isPrimary: Boolean,
    val isBuiltIn: Boolean,
) {
    /**
     * "Samsung Odyssey G70NC"-style name, or null when the monitor reports neither a model name
     * nor a known manufacturer (callers fall back to a localized "Built-in display"/"Screen").
     */
    val displayName: String?
        get() {
            val model = modelName?.takeIf { it.isNotBlank() }
            val maker = brand ?: manufacturerCode
            return when {
                model == null -> maker
                maker == null || model.startsWith(maker, ignoreCase = true) -> model
                else -> "$maker $model"
            }
        }
}

/**
 * How Windows spreads the desktop over the connected screens - the Win+P "Project" choices, plus
 * [SINGLE] (a lone external screen with no laptop panel, e.g. a desktop PC) and
 * [DUPLICATE_AND_EXTEND] (3+ screens with some duplicated, only possible via Display settings).
 */
enum class ScreenMode { PC_SCREEN_ONLY, SECOND_SCREEN_ONLY, SINGLE, DUPLICATE, EXTEND, DUPLICATE_AND_EXTEND }

/**
 * Win32 display enumeration. Every call is safe to make from any thread and never throws: any
 * failure (non-Windows OS, missing API, unexpected driver answer) yields an empty list / null.
 */
object ScreenManager {

    /** The monitors currently attached and active on the desktop, primary first. */
    fun listScreens(): List<ConnectedScreen> = try {
        queryActiveScreens().sortedWith(compareByDescending<ConnectedScreen> { it.isPrimary }.thenBy { it.gdiDeviceName })
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * The current [ScreenMode] for [screens] (a [listScreens] result), null if there are none.
     * Derived from the active paths - duplicated screens share one desktop source - rather than
     * only from the topology Windows stores, which says nothing about a customized 3+ screen
     * layout; the stored topology only tells "second screen only" from a lone desktop monitor.
     */
    fun screenMode(screens: List<ConnectedScreen>): ScreenMode? {
        if (screens.isEmpty()) return null
        val sourceCount = screens.map { it.gdiDeviceName }.distinct().size
        return when {
            screens.size == 1 && screens[0].isBuiltIn -> ScreenMode.PC_SCREEN_ONLY
            screens.size == 1 -> if (databaseTopology() == TopologyExternal) ScreenMode.SECOND_SCREEN_ONLY else ScreenMode.SINGLE
            sourceCount == 1 -> ScreenMode.DUPLICATE
            sourceCount == screens.size -> ScreenMode.EXTEND
            else -> ScreenMode.DUPLICATE_AND_EXTEND
        }
    }

    /** DISPLAYCONFIG_TOPOLOGY_ID of the current Win+P choice, null if none is stored or the call fails. */
    private fun databaseTopology(): Int? = try {
        val api = User32DisplayConfig.INSTANCE
        val pathCount = IntByReference()
        val modeCount = IntByReference()
        if (api.GetDisplayConfigBufferSizes(QdcDatabaseCurrent, pathCount, modeCount) != ErrorSuccess) {
            null
        } else {
            val paths = Memory(maxOf(1, pathCount.value).toLong() * PathInfoSize).apply { clear() }
            val modes = Memory(maxOf(1, modeCount.value).toLong() * ModeInfoSize).apply { clear() }
            val topology = Memory(4).apply { clear() }
            if (api.QueryDisplayConfig(QdcDatabaseCurrent, pathCount, paths, modeCount, modes, topology) == ErrorSuccess) {
                topology.getInt(0).takeIf { it != 0 }
            } else {
                null
            }
        }
    } catch (e: Throwable) {
        null
    }

    /**
     * Native handle of [window], or null if it has no peer yet. Read it on the UI thread (JAWT),
     * then hand it to [gdiDeviceNameOf] from wherever.
     */
    fun windowHandle(window: java.awt.Window): Pointer? = try {
        Native.getWindowPointer(window)
    } catch (e: Throwable) {
        null
    }

    /**
     * GDI name (`\\.\DISPLAY1`, ...) of the monitor showing the largest part of the window
     * [hwnd] - match it against [ConnectedScreen.gdiDeviceName].
     */
    fun gdiDeviceNameOf(hwnd: Pointer): String? = try {
        val monitor = User32Screens.INSTANCE.MonitorFromWindow(hwnd, MonitorDefaultToNearest)
        if (monitor == null) {
            null
        } else {
            // MONITORINFOEXW: cbSize, rcMonitor, rcWork, dwFlags, then szDevice[32] at offset 40.
            val info = Memory(MonitorInfoExSize.toLong()).apply { clear() }
            info.setInt(0, MonitorInfoExSize)
            if (User32Screens.INSTANCE.GetMonitorInfoW(monitor, info)) info.getWideString(40).takeIf { it.isNotEmpty() } else null
        }
    } catch (e: Throwable) {
        null
    }

    private fun queryActiveScreens(): List<ConnectedScreen> {
        val api = User32DisplayConfig.INSTANCE
        val pathCount = IntByReference()
        val modeCount = IntByReference()
        var paths: Memory
        var modes: Memory
        // The configuration can change between the size query and the real query (a monitor
        // being plugged right then): retry a few times on ERROR_INSUFFICIENT_BUFFER.
        var attempt = 0
        while (true) {
            if (api.GetDisplayConfigBufferSizes(QdcOnlyActivePaths, pathCount, modeCount) != ErrorSuccess) return emptyList()
            if (pathCount.value <= 0) return emptyList()
            paths = Memory(pathCount.value.toLong() * PathInfoSize).apply { clear() }
            modes = Memory(maxOf(1, modeCount.value).toLong() * ModeInfoSize).apply { clear() }
            val result = api.QueryDisplayConfig(QdcOnlyActivePaths, pathCount, paths, modeCount, modes, null)
            if (result == ErrorSuccess) break
            if (result != ErrorInsufficientBuffer || ++attempt >= 3) return emptyList()
        }

        val screens = mutableListOf<ConnectedScreen>()
        for (i in 0 until pathCount.value) {
            val p = i.toLong() * PathInfoSize
            // DISPLAYCONFIG_PATH_INFO: sourceInfo (20 bytes) then targetInfo (48 bytes) then flags.
            val sourceAdapterLow = paths.getInt(p + 0)
            val sourceAdapterHigh = paths.getInt(p + 4)
            val sourceId = paths.getInt(p + 8)
            val sourceModeIdx = paths.getInt(p + 12)
            val targetAdapterLow = paths.getInt(p + 20)
            val targetAdapterHigh = paths.getInt(p + 24)
            val targetId = paths.getInt(p + 28)
            val outputTechnology = paths.getInt(p + 36)

            // DISPLAYCONFIG_TARGET_DEVICE_NAME (420 bytes): header (20), flags, outputTechnology,
            // edidManufactureId (u16 @28), edidProductCodeId, connectorInstance,
            // monitorFriendlyDeviceName[64] @36, monitorDevicePath[128] @164.
            val target = Memory(TargetDeviceNameSize.toLong()).apply { clear() }
            writeHeader(target, DeviceInfoGetTargetName, TargetDeviceNameSize, targetAdapterLow, targetAdapterHigh, targetId)
            val hasTargetName = api.DisplayConfigGetDeviceInfo(target) == ErrorSuccess
            val targetFlags = if (hasTargetName) target.getInt(20) else 0
            // Only trusted when it comes from the EDID: otherwise it's a generic driver name.
            val friendlyName = if (hasTargetName && targetFlags and TargetNameFlagFriendlyNameFromEdid != 0) {
                target.getWideString(36).trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val manufacturerCode = if (hasTargetName && targetFlags and TargetNameFlagEdidIdsValid != 0) {
                decodePnpId(target.getShort(28).toInt() and 0xFFFF)
            } else {
                null
            }
            val devicePath = if (hasTargetName) target.getWideString(164).takeIf { it.isNotEmpty() } else null

            // DISPLAYCONFIG_SOURCE_DEVICE_NAME (84 bytes): header (20), viewGdiDeviceName[32] @20.
            val source = Memory(SourceDeviceNameSize.toLong()).apply { clear() }
            writeHeader(source, DeviceInfoGetSourceName, SourceDeviceNameSize, sourceAdapterLow, sourceAdapterHigh, sourceId)
            val gdiName = if (api.DisplayConfigGetDeviceInfo(source) == ErrorSuccess) source.getWideString(20) else ""

            // DISPLAYCONFIG_MODE_INFO (64 bytes): infoType, id, adapterId, then the union @16;
            // DISPLAYCONFIG_SOURCE_MODE: width, height, pixelFormat, position {x, y}.
            val modeOffset = sourceModeOffset(modes, modeCount.value, sourceModeIdx, sourceId, sourceAdapterLow, sourceAdapterHigh)
            val width = modeOffset?.let { modes.getInt(it + 16) } ?: 0
            val height = modeOffset?.let { modes.getInt(it + 20) } ?: 0
            val posX = modeOffset?.let { modes.getInt(it + 28) } ?: -1
            val posY = modeOffset?.let { modes.getInt(it + 32) } ?: -1

            screens += ConnectedScreen(
                id = devicePath ?: "$gdiName#$targetAdapterHigh:$targetAdapterLow:$targetId",
                gdiDeviceName = gdiName,
                modelName = friendlyName,
                manufacturerCode = manufacturerCode,
                brand = manufacturerCode?.let { PnpBrands[it] },
                widthPx = width,
                heightPx = height,
                // The primary monitor is the one whose desktop origin is (0, 0).
                isPrimary = posX == 0 && posY == 0,
                isBuiltIn = outputTechnology == OutputTechnologyInternal ||
                    outputTechnology == OutputTechnologyDisplayPortEmbedded ||
                    outputTechnology == OutputTechnologyUdiEmbedded,
            )
        }
        return screens.distinctBy { it.id }
    }

    private fun writeHeader(m: Memory, type: Int, size: Int, adapterLow: Int, adapterHigh: Int, id: Int) {
        m.setInt(0, type)
        m.setInt(4, size)
        m.setInt(8, adapterLow)
        m.setInt(12, adapterHigh)
        m.setInt(16, id)
    }

    /** Byte offset of the path's source mode in [modes], by index when valid, else by search. */
    private fun sourceModeOffset(modes: Memory, count: Int, index: Int, sourceId: Int, adapterLow: Int, adapterHigh: Int): Long? {
        fun isSourceMode(o: Long) = modes.getInt(o) == ModeInfoTypeSource
        if (index in 0 until count && isSourceMode(index.toLong() * ModeInfoSize)) return index.toLong() * ModeInfoSize
        for (i in 0 until count) {
            val o = i.toLong() * ModeInfoSize
            if (isSourceMode(o) && modes.getInt(o + 4) == sourceId && modes.getInt(o + 8) == adapterLow && modes.getInt(o + 12) == adapterHigh) return o
        }
        return null
    }

    /**
     * EDID packs the manufacturer as three 5-bit letters ('A' = 1) in a big-endian u16;
     * DisplayConfig hands it over as read little-endian, hence the byte swap (with the unswapped
     * order as a fallback in case a driver already swapped it).
     */
    private fun decodePnpId(raw: Int): String? {
        fun letters(v: Int): String? {
            val chars = intArrayOf((v shr 10) and 0x1F, (v shr 5) and 0x1F, v and 0x1F)
            if (chars.any { it !in 1..26 }) return null
            return chars.joinToString("") { ('A' + it - 1).toString() }
        }
        val swapped = ((raw and 0xFF) shl 8) or ((raw shr 8) and 0xFF)
        return letters(swapped) ?: letters(raw)
    }

    /** Common monitor manufacturers' PNP IDs (UEFI PNP ID registry). Unknown codes show as-is. */
    private val PnpBrands = mapOf(
        "ACI" to "ASUS", "ACR" to "Acer", "AOC" to "AOC", "APP" to "Apple", "AUO" to "AU Optronics",
        "AUS" to "ASUS", "BNQ" to "BenQ", "BOE" to "BOE", "CMN" to "Innolux", "CMO" to "Chi Mei",
        "DEL" to "Dell", "EIZ" to "EIZO", "ENC" to "EIZO", "FUS" to "Fujitsu", "GBT" to "Gigabyte",
        "GGL" to "Google", "GSM" to "LG", "HPN" to "HP", "HSD" to "HannStar", "HWP" to "HP",
        "IVM" to "iiyama", "LEN" to "Lenovo", "LGD" to "LG Display", "LPL" to "LG Philips",
        "MEI" to "Panasonic", "MSI" to "MSI", "MST" to "MStar", "NEC" to "NEC", "NCP" to "Nreal",
        "PHL" to "Philips", "SAM" to "Samsung", "SDC" to "Samsung Display", "SEC" to "Samsung",
        "SHP" to "Sharp", "SNY" to "Sony", "TCL" to "TCL", "TSB" to "Toshiba", "VSC" to "ViewSonic",
        "XMI" to "Xiaomi", "HKC" to "HKC", "SKG" to "Skyworth", "HIQ" to "Hyundai", "MAX" to "Belinea",
        "VIZ" to "Vizio", "HSP" to "Hisense", "HEC" to "Hisense", "LCT" to "LG", "ZOW" to "BenQ Zowie",
    )

    private const val ErrorSuccess = 0
    private const val ErrorInsufficientBuffer = 122
    private const val QdcOnlyActivePaths = 0x2
    private const val QdcDatabaseCurrent = 0x4
    private const val TopologyExternal = 0x8
    private const val PathInfoSize = 72
    private const val ModeInfoSize = 64
    private const val ModeInfoTypeSource = 1
    private const val DeviceInfoGetSourceName = 1
    private const val DeviceInfoGetTargetName = 2
    private const val SourceDeviceNameSize = 84
    private const val TargetDeviceNameSize = 420
    private const val TargetNameFlagFriendlyNameFromEdid = 0x1
    private const val TargetNameFlagEdidIdsValid = 0x4
    private const val OutputTechnologyInternal = 0x80000000.toInt()
    private const val OutputTechnologyDisplayPortEmbedded = 11
    private const val OutputTechnologyUdiEmbedded = 13
    private const val MonitorDefaultToNearest = 2
    private const val MonitorInfoExSize = 104
}

@Suppress("FunctionName")
private interface User32DisplayConfig : Library {
    fun GetDisplayConfigBufferSizes(flags: Int, numPathArrayElements: IntByReference, numModeInfoArrayElements: IntByReference): Int
    fun QueryDisplayConfig(flags: Int, numPathArrayElements: IntByReference, pathArray: Pointer, numModeInfoArrayElements: IntByReference, modeInfoArray: Pointer, currentTopologyId: Pointer?): Int
    fun DisplayConfigGetDeviceInfo(requestPacket: Pointer): Int

    companion object {
        val INSTANCE: User32DisplayConfig by lazy { Native.load("user32", User32DisplayConfig::class.java) }
    }
}

@Suppress("FunctionName")
private interface User32Screens : Library {
    fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
    fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean

    companion object {
        val INSTANCE: User32Screens by lazy { Native.load("user32", User32Screens::class.java) }
    }
}

/**
 * Compose-observable view of [ScreenManager], kept by [AppViewModel] so it survives the Window
 * recreations Main.kt does: [poll] is called every couple of seconds from Main.kt (Windows only
 * notifies display changes through a window message, which Compose doesn't expose; polling
 * QueryDisplayConfig costs well under a millisecond).
 */
class ScreenWatcher {
    /** The connected screens, primary first. Empty until the first [poll] (or if enumeration fails). */
    var screens by mutableStateOf<List<ConnectedScreen>>(emptyList())
        private set

    /** How the desktop is spread over [screens] (duplicate, extend...), null until the first [poll]. */
    var mode by mutableStateOf<ScreenMode?>(null)
        private set

    /** GDI name of the monitor the app window is on (see [ConnectedScreen.gdiDeviceName]). */
    var currentGdiDeviceName by mutableStateOf<String?>(null)
        private set

    /**
     * The last screen plugged in while the app was running - a new instance per plug, so a toast
     * keyed on it re-flashes even when the same monitor is unplugged and plugged back. Never set
     * for the screens already there at the first poll.
     */
    var newlyConnected by mutableStateOf<NewScreenEvent?>(null)
        private set

    class NewScreenEvent(val screen: ConnectedScreen, val atMs: Long = System.currentTimeMillis())

    private var knownIds: Set<String>? = null

    /** The screen(s) the window is on - several in "duplicate" mode. */
    val currentScreens: List<ConnectedScreen>
        get() = screens.filter { it.gdiDeviceName == currentGdiDeviceName }

    /** Blocking (call it off the UI thread); [hwnd] from [ScreenManager.windowHandle]. */
    fun poll(hwnd: Pointer?): PollResult {
        val screens = ScreenManager.listScreens()
        return PollResult(
            screens = screens,
            mode = ScreenManager.screenMode(screens),
            currentGdiDeviceName = hwnd?.let(ScreenManager::gdiDeviceNameOf),
        )
    }

    class PollResult(val screens: List<ConnectedScreen>, val mode: ScreenMode?, val currentGdiDeviceName: String?)

    /** Applies a [poll] result - on the UI thread, since it writes Compose state. */
    fun apply(result: PollResult) {
        val ids = result.screens.map { it.id }.toSet()
        val previous = knownIds
        // An empty answer is a transient failure (or a non-Windows OS), not "every screen unplugged":
        // keep the baseline so the screens don't all read as new on the next successful poll.
        if (result.screens.isEmpty()) return
        if (previous != null) {
            result.screens.lastOrNull { it.id !in previous }?.let { newlyConnected = NewScreenEvent(it) }
        }
        knownIds = ids
        if (result.screens != screens) screens = result.screens
        mode = result.mode
        currentGdiDeviceName = result.currentGdiDeviceName
    }
}
