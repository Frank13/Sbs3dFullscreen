import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.camera3d.camera.feature_playlists.ui.fullscreenScrollDurationMs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.crash_loop_message
import sbs3dViewer.resources.icon
import sbs3dViewer.resources.playlist_add_music_dialog_title
import sbs3dViewer.resources.playlist_add_photos_directory_dialog_title
import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

private val Key.isShift: Boolean get() = this == Key.ShiftLeft || this == Key.ShiftRight
private val Key.isCtrl: Boolean get() = this == Key.CtrlLeft || this == Key.CtrlRight

fun main(args: Array<String>) {
    try {
        CrashReporting.install()
        runApp(args)
    } catch (t: Throwable) {
        reportStartupCrash(t)
        throw t
    }
}

// The packaged app's jpackage launcher turns any exception escaping main() into a bare "Failed to
// launch JVM" box with no stack trace, so surface the real error: shown in a dialog and saved to
// ~/sbs3dViewer-crash.log (see CrashReporting), and sent to Sentry with consent.
private fun reportStartupCrash(t: Throwable) {
    val trace = t.stackTraceToString()
    CrashReporting.report(t, source = "startup")
    CrashReporting.flush()
    runCatching {
        val text = javax.swing.JTextArea(trace, 25, 100).apply { isEditable = false }
        javax.swing.JOptionPane.showMessageDialog(
            null, javax.swing.JScrollPane(text), "SBS 3D Viewer - startup error", javax.swing.JOptionPane.ERROR_MESSAGE
        )
    }
}

private fun runApp(args: Array<String>) = application {
    // Windows launches the app with the file path as an argument when it's opened
    // via a file association (double-click, "Open with sbs3dViewer", etc.).
    val initialFile = args.firstOrNull()?.let(::File)?.takeIf { it.isFile }
    val windowState = rememberWindowState(
        placement = if (initialFile != null) WindowPlacement.Maximized else WindowPlacement.Floating
    )
    val viewModel = remember { AppViewModel(initialFile) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    // Mirrors Analytics.hasAnsweredConsent (backed by Preferences, not Compose state) so the
    // consent dialog below disappears immediately once answered, without waiting on some other
    // unrelated recomposition to notice the Preferences value changed.
    var analyticsConsentAnswered by remember { mutableStateOf(Analytics.hasAnsweredConsent) }
    remember { WhatsNewTracker.onLaunch() }
    LaunchedEffect(Unit) {
        // No-ops until consent is granted - see Analytics.logEvent. Confirming the dialog below
        // fires this same event explicitly, so a first-run "Allow" still counts as a launch.
        Analytics.logEvent("app_launch")
    }
    // Announced once after an update, on the Welcome screen only (a launch via file association
    // goes straight to the viewer and must not be interrupted) and after the consent dialog.
    var whatsNewRelease by remember { mutableStateOf<WhatsNewRelease?>(null) }
    var whatsNewChecked by remember { mutableStateOf(false) }
    LaunchedEffect(analyticsConsentAnswered, viewModel.screen) {
        if (!whatsNewChecked && analyticsConsentAnswered && viewModel.screen == Screen.Welcome) {
            whatsNewChecked = true
            whatsNewRelease = WhatsNewTracker.releaseToShow()?.also { Analytics.logEvent("whats_new_shown") }
        }
    }
    // Hoisted above key(undecorated) below (which disposes/recreates the whole Window subtree,
    // including anything remembered inside GalleryScreen) so the scroll position survives
    // Gallery -> ImageView -> Gallery round-trips.
    val galleryListState = rememberLazyListState()
    // Same hoisting for the playlist editor, so its scroll position survives PlaylistEdit ->
    // PlaylistItem -> PlaylistEdit (and playing the playlist) round-trips. Reset whenever we're
    // back on a screen a (possibly different) playlist gets opened from, so a newly opened
    // playlist starts at the top.
    var playlistEditListState by remember { mutableStateOf(LazyListState()) }
    // Same for the playlist list (PlaylistList -> PlaylistEdit/playing -> PlaylistList), reset
    // only when leaving the playlists section through the Welcome screen.
    var playlistListState by remember { mutableStateOf(LazyListState()) }
    LaunchedEffect(viewModel.screen) {
        if (viewModel.screen == Screen.PlaylistList || viewModel.screen == Screen.Welcome) {
            playlistEditListState = LazyListState()
        }
        if (viewModel.screen == Screen.Welcome) playlistListState = LazyListState()
    }
    val undecorated = viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView

    // Remembers the window's placement/size/position from just before entering the
    // undecorated+Maximized "fullscreen" mode, so Escape can restore it exactly instead of
    // just flipping placement back to Floating (which left the window sized to fill the
    // screen, i.e. still looking fullscreen, just with borders).
    var previousPlacement by remember { mutableStateOf(WindowPlacement.Floating) }
    var previousSize by remember { mutableStateOf(windowState.size) }
    var previousPosition by remember { mutableStateOf(windowState.position) }

    // Toggled open/closed by ImageScreen each time Shift or Ctrl is pressed (see
    // onPreviewKeyEvent below).
    var showImageInfoPanel by remember { mutableStateOf(false) }

    // Mirrors ImageScreen's own anyDialogShown (a confirmation dialog, the share picker, the
    // stereo-issue comment TextField, ...) - under "shrink controls" these render in-scene
    // rather than as a separate native window (see ShrinkControls.kt), so without this,
    // onPreviewKeyEvent below would keep hijacking Escape/Space/arrow keys/"A" as app-wide
    // shortcuts instead of letting them reach the dialog's own TextField/buttons.
    var imageDialogShown by remember { mutableStateOf(false) }

    // Currently-held direction keys during manual-align mode, key -> press-start timestamp (ms).
    // Plain (non-Compose-state) map: read/written from onPreviewKeyEvent and the tick loop below,
    // never needs to trigger recomposition on its own.
    val manualAlignKeyPressStart = remember { mutableMapOf<Key, Long>() }
    val manualAlignDirectionKeys = remember { setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown) }

    // Drives the accelerating nudge (viewModel.manualAlignStepPercent per second, 10x that once a
    // key's been held 5s continuously - see AlignButtonsRow's Manual Align button and the settings
    // menu's manual-align-step row) for whichever direction keys onPreviewKeyEvent below is
    // currently tracking in manualAlignKeyPressStart, independent of the OS's own key-repeat
    // rate/timing. Deltas are fractions of the eye-half's width(X)/height(Y) - see
    // PhotoToolsState.manualAlignOffsetX/Y - so, unlike the old whole-pixel version, no
    // remainder/rounding bookkeeping is needed here: the fraction accumulates continuously.
    LaunchedEffect(viewModel.photoTools.manualAlignMode) {
        if (!viewModel.photoTools.manualAlignMode) {
            manualAlignKeyPressStart.clear()
            return@LaunchedEffect
        }
        var lastTickMs = System.currentTimeMillis()
        while (true) {
            delay(50.milliseconds)
            val now = System.currentTimeMillis()
            val dtSeconds = (now - lastTickMs) / 1000f
            lastTickMs = now
            val stepFraction = viewModel.manualAlignStepPercent / 100f
            var dx = 0f
            var dy = 0f
            for ((key, pressedAtMs) in manualAlignKeyPressStart) {
                val heldMs = now - pressedAtMs
                val rateFractionPerSec = if (heldMs < 5000) stepFraction else stepFraction * 10f
                val delta = rateFractionPerSec * dtSeconds
                when (key) {
                    Key.DirectionRight -> dx += delta
                    Key.DirectionLeft -> dx -= delta
                    Key.DirectionDown -> dy += delta
                    Key.DirectionUp -> dy -= delta
                    else -> {}
                }
            }
            if (dx != 0f || dy != 0f) viewModel.nudgeManualAlign(dx, dy)
        }
    }

    // True from the moment enterFullscreen() is called until whatever's being entered is ready to
    // show (the first photo's decode, a video's first frame, or immediately for a playlist's title
    // slide) - drives FullscreenLoadingOverlay. Hoisted above key(undecorated) so it survives the
    // Window recreation that happens at the same time.
    var isEnteringFullscreen by remember { mutableStateOf(false) }
    var enteredFullscreenAtMs by remember { mutableStateOf(0L) }

    // Photo decode is often fast enough (well under a second for typical camera JPEGs) that
    // clearing isEnteringFullscreen the instant it's done makes the overlay flash for only a
    // frame or two - imperceptible rather than reassuring. Keeping it up for at least this long
    // (padding out the remainder with a delay if the real work finished sooner) makes it read as
    // a deliberate "opening..." message instead of a glitch.
    val minFullscreenLoadingMs = 500L
    val finishEnteringFullscreen = {
        val remaining = minFullscreenLoadingMs - (System.currentTimeMillis() - enteredFullscreenAtMs)
        if (remaining > 0) {
            coroutineScope.launch {
                delay(remaining.milliseconds)
                isEnteringFullscreen = false
            }
        } else {
            isEnteringFullscreen = false
        }
        Unit
    }

    // Shared by the Escape key handler below and ImageScreen's settings-menu "exit fullscreen"
    // item, so both paths restore the window the same way.
    val exitFullscreen = {
        windowState.placement = previousPlacement
        windowState.size = previousSize
        windowState.position = previousPosition
        showImageInfoPanel = false
        isEnteringFullscreen = false
        viewModel.closeImageView()
    }

    // Bumped by the window exception handler below to throw away a crashed Window and compose a
    // fresh one (via the same key() that handles undecorated changes).
    var windowGeneration by remember { mutableStateOf(0) }
    var showCrashRecoveredDialog by remember { mutableStateOf(false) }
    val recentWindowCrashesMs = remember { ArrayDeque<Long>() }

    // Replaces Compose's default handler (error dialog, then close the window - which, as the only
    // window, quits the app) for exceptions thrown while composing/drawing/handling input: report
    // it, then reset to Welcome in a new window. A crash loop (e.g. Welcome itself throwing) still
    // quits, after telling the user.
    @OptIn(ExperimentalComposeUiApi::class)
    val windowExceptionHandlerFactory = remember {
        WindowExceptionHandlerFactory { _ ->
            var handled = false
            WindowExceptionHandler { throwable ->
                // A broken composition keeps throwing on every frame until the window is gone.
                if (handled) return@WindowExceptionHandler
                handled = true
                CrashReporting.report(throwable, source = "window")
                val now = System.currentTimeMillis()
                recentWindowCrashesMs.addLast(now)
                while (recentWindowCrashesMs.first() < now - 30_000) recentWindowCrashesMs.removeFirst()
                // Deferred: the handler runs mid-frame, inside the very scene being torn down.
                SwingUtilities.invokeLater {
                    if (recentWindowCrashesMs.size >= 3) {
                        CrashReporting.flush()
                        val message = runCatching { runBlocking { getString(Res.string.crash_loop_message) } }
                            .getOrDefault(throwable.toString())
                        JOptionPane.showMessageDialog(null, message, "SBS 3D Viewer", JOptionPane.ERROR_MESSAGE)
                        exitApplication()
                        return@invokeLater
                    }
                    if (viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView) {
                        windowState.placement = previousPlacement
                        windowState.size = previousSize
                        windowState.position = previousPosition
                    }
                    showImageInfoPanel = false
                    imageDialogShown = false
                    isEnteringFullscreen = false
                    viewModel.resetAfterCrash()
                    showCrashRecoveredDialog = true
                    windowGeneration++
                }
            }
        }
    }

    // undecorated can only be set before the window's peer is created, so the
    // whole Window is disposed and recreated (via key()) whenever it changes.
    @OptIn(ExperimentalComposeUiApi::class)
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides windowExceptionHandlerFactory) {
        key(undecorated, windowGeneration) {
            Window(
                onCloseRequest = ::exitApplication,
                state = windowState,
                title = "SBS 3D Viewer",
                undecorated = undecorated,
                icon = painterResource(Res.drawable.icon),
            ) {
                LaunchedEffect(viewModel.screen) {
                    focusRequester.requestFocus()
                }

                LaunchedEffect(viewModel.screen) {
                    Analytics.logEvent("screen_view", mapOf("screen_name" to viewModel.screen.name))
                }

                LaunchedEffect(viewModel.alignToast) {
                    viewModel.alignToast?.let { toast ->
                        if (!toast.success) Analytics.logEvent("app_error", mapOf("type" to "align"))
                    }
                }

                LaunchedEffect(viewModel.saveToast) {
                    viewModel.saveToast?.let { toast ->
                        if (!toast.success) Analytics.logEvent("app_error", mapOf("type" to "save"))
                    }
                }

                LaunchedEffect(viewModel.share.toast) {
                    // Only ever bumped on Share.EmailResult.FAILED - see ShareToast's doc comment.
                    if (viewModel.share.toast != null) Analytics.logEvent("app_error", mapOf("type" to "share"))
                }

                // Clears isEnteringFullscreen for the paths that don't decode a photo (a playlist's
                // title/end slide, a standalone video, or a video mid-playlist - VideoScreen/
                // PlaylistVideoSlide show their own black screen until the first frame arrives). The
                // photo path instead clears it via ImageScreen's onImageLoaded.
                LaunchedEffect(viewModel.screen, viewModel.playlistSlideKind, viewModel.currentPlaylistItem) {
                    if (isEnteringFullscreen &&
                        (viewModel.screen == Screen.VideoView ||
                            (viewModel.screen == Screen.ImageView && viewModel.playlistSlideKind != null &&
                                (viewModel.playlistSlideKind != PlaylistSlideKind.PHOTO || viewModel.currentPlaylistItem?.isVideo == true)))
                    ) {
                        finishEnteringFullscreen()
                    }
                }

                // Decodes the immediate Next/Previous neighbors into ImageDecodeCache ahead of time,
                // so a Next/Previous press usually lands on an already-decoded bitmap instead of
                // blocking on a fresh disk read + JPEG decode - see ImageScreen's use of the same
                // cache. Harmless to run for a playlist's title/end slide (currentImage is null there,
                // index just out of imageFiles' bounds) or a video (ImageDecodeCache.warm no-ops on
                // non-photo extensions).
                // Pre-reads the EXIF3D favorite/warning flags of the whole selection into
                // Exif3dFlagsCache, so the photo filters (see AppViewModel.visiblePhotos) hit the cache
                // instead of parsing every file's EXIF on the UI thread at each Next/Previous. Skipped
                // for a playlist, where the filters never apply.
                LaunchedEffect(viewModel.screen, viewModel.imageFiles, viewModel.playingPlaylist) {
                    if (viewModel.screen == Screen.ImageView && viewModel.playingPlaylist == null) {
                        val files = viewModel.imageFiles
                        withContext(Dispatchers.IO) { Exif3dFlagsCache.warm(files) }
                    }
                }

                LaunchedEffect(viewModel.screen, viewModel.imageFiles, viewModel.currentImageIndex) {
                    if (viewModel.screen == Screen.ImageView) {
                        viewModel.imageFiles.getOrNull(viewModel.currentImageIndex - 1)?.let(ImageDecodeCache::warm)
                        viewModel.imageFiles.getOrNull(viewModel.currentImageIndex + 1)?.let(ImageDecodeCache::warm)
                    }
                }

                CompositionLocalProvider(LocalAppLocale provides viewModel.language) {
                    // stringResource() re-reads Locale.getDefault() when it's newly composed,
                    // so the subtree must be recreated (via key()) whenever the language changes.
                    key(viewModel.language) {
                        AppTheme {
                            val inViewer = viewModel.screen == Screen.ImageView || viewModel.screen == Screen.VideoView
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(focusRequester)
                                    .focusable()
                                    .onPreviewKeyEvent { event ->
                                        val tools = viewModel.photoTools
                                        // The active edit tool's own "discard" action, null when none is active.
                                        val cancelActiveTool: (() -> Unit)? = when {
                                            tools.manualAlignMode -> viewModel::cancelManualAlign
                                            tools.cropMode -> viewModel::cancelCrop
                                            tools.spotIssuesMode -> viewModel::cancelSpotIssues
                                            tools.clickAlignMode -> viewModel::cancelClickAlign
                                            else -> null
                                        }
                                        if (imageDialogShown) {
                                            // Leave every key untouched (see imageDialogShown's doc) so
                                            // it reaches the in-scene dialog's own TextField/buttons
                                            // instead of being swallowed as an app-wide shortcut -
                                            // Escape closing the dialog, Space typing a literal space,
                                            // and arrow keys moving the text caret all rely on this.
                                            false
                                        } else if (cancelActiveTool != null) {
                                            // While an edit tool is active: Escape cancels it instead of
                                            // exiting fullscreen, Shift/Ctrl still toggle the info panel,
                                            // and every other key-down is swallowed so nothing else -
                                            // navigation, auto-align's "A" shortcut - can mutate state out
                                            // from under the pending edit. Two tool-specific exceptions:
                                            // manual align's arrow keys feed the tick-loop LaunchedEffect
                                            // above, and click align repurposes Shift (held) for its
                                            // alignment preview (see ClickAlign.Preview) - the info panel
                                            // is already open there by definition, so only Ctrl toggles it.
                                            when {
                                                tools.manualAlignMode && event.key in manualAlignDirectionKeys -> {
                                                    when (event.type) {
                                                        KeyEventType.KeyDown -> manualAlignKeyPressStart.getOrPut(event.key) { System.currentTimeMillis() }
                                                        KeyEventType.KeyUp -> manualAlignKeyPressStart.remove(event.key)
                                                        else -> {}
                                                    }
                                                    true
                                                }
                                                tools.clickAlignMode && event.key.isShift -> {
                                                    when (event.type) {
                                                        KeyEventType.KeyDown -> viewModel.setClickAlignPreviewActive(true)
                                                        KeyEventType.KeyUp -> viewModel.setClickAlignPreviewActive(false)
                                                        else -> {}
                                                    }
                                                    true
                                                }
                                                event.type != KeyEventType.KeyDown -> false
                                                event.key == Key.Escape -> {
                                                    cancelActiveTool()
                                                    true
                                                }
                                                event.key.isShift || event.key.isCtrl -> {
                                                    showImageInfoPanel = !showImageInfoPanel
                                                    true
                                                }
                                                else -> true
                                            }
                                        } else {
                                            // Toggles on the key-down of Shift/Ctrl itself (not on every
                                            // event where one happens to be held as a modifier), so a
                                            // press opens the panel and the next press closes it again -
                                            // holding the key no longer matters.
                                            if (inViewer && event.type == KeyEventType.KeyDown && (event.key.isShift || event.key.isCtrl)) {
                                                showImageInfoPanel = !showImageInfoPanel
                                            }
                                            if (!inViewer || event.type != KeyEventType.KeyDown) {
                                                false
                                            } else when (event.key) {
                                                Key.Escape -> {
                                                    exitFullscreen()
                                                    true
                                                }
                                                Key.Spacebar, Key.DirectionRight -> {
                                                    viewModel.showNextImage()
                                                    true
                                                }
                                                Key.DirectionLeft -> {
                                                    viewModel.showPreviousImage()
                                                    true
                                                }
                                                Key.A -> {
                                                    // Runs on a background thread via performAutoAlign
                                                    // (same path as AlignButtonsRow's Correct zoom button),
                                                    // which also no-ops if a task is already running.
                                                    // Only meaningful for still images, not video.
                                                    if (viewModel.screen == Screen.ImageView) {
                                                        viewModel.currentImage?.let { file ->
                                                            coroutineScope.launch {
                                                                viewModel.performAutoAlign(file, AlignKind.AFFINE)
                                                            }
                                                        }
                                                    }
                                                    true
                                                }
                                                else -> false
                                            }
                                        }
                                    }
                            ) {
                                // Undecorated + Maximized ("borderless fullscreen") rather than
                                // WindowPlacement.Fullscreen: on Windows, Fullscreen puts the window
                                // into real OS exclusive full-screen mode (GraphicsDevice.fullScreenWindow),
                                // which Windows auto-minimizes if the window loses focus - e.g. when the
                                // 3D monitor's own "activate 3D" popup steals focus. Maximized is a normal
                                // window, so it just deactivates instead of getting iconified.
                                fun enterFullscreen() {
                                    previousPlacement = windowState.placement
                                    previousSize = windowState.size
                                    previousPosition = windowState.position
                                    windowState.placement = WindowPlacement.Maximized
                                    isEnteringFullscreen = true
                                    enteredFullscreenAtMs = System.currentTimeMillis()
                                }

                                when (viewModel.screen) {
                                    Screen.Welcome -> WelcomeScreen(
                                        window = window,
                                        language = viewModel.language,
                                        onLanguageChosen = viewModel::onLanguageChosen,
                                        useNewOpenCv5 = viewModel.useNewOpenCv5,
                                        onUseNewOpenCv5Chosen = viewModel::onUseNewOpenCv5Chosen,
                                        audioOutputDeviceId = viewModel.audioOutputDeviceId,
                                        onAudioOutputDeviceChosen = viewModel::onAudioOutputDeviceChosen,
                                        halveLeftRightImages = viewModel.halveLeftRightImages,
                                        onHalveLeftRightImagesChosen = viewModel::onHalveLeftRightImagesChosen,
                                        shrinkControls = viewModel.shrinkControls,
                                        onShrinkControlsChosen = viewModel::onShrinkControlsChosen,
                                        onFilesChosen = { files ->
                                            if (viewModel.onFilesChosen(files)) enterFullscreen()
                                        },
                                        onImportPlaylist = { folder ->
                                            viewModel.importPlaylistFolder(folder)
                                        },
                                        onOpenPlaylistList = { viewModel.openPlaylistList() },
                                        onOpenGallery = { folder -> viewModel.openGallery(folder) },
                                        onOpenAbout = { viewModel.openAbout() },
                                    )

                                    Screen.About -> AboutScreen(onBack = { viewModel.closeAbout() })

                                    Screen.Gallery -> GalleryScreen(
                                        groups = viewModel.gallery.groups,
                                        expandedGroups = viewModel.gallery.expandedGroups,
                                        listState = galleryListState,
                                        scrollTarget = viewModel.gallery.scrollTarget,
                                        onScrollTargetConsumed = { viewModel.consumeGalleryScrollTarget() },
                                        onToggleGroup = { path -> viewModel.toggleGalleryGroup(path) },
                                        onOpenImage = { group, index ->
                                            if (viewModel.openGalleryImage(group, index)) enterFullscreen()
                                        },
                                        onBack = {
                                            if (viewModel.gallery.pickerMode) viewModel.cancelGalleryPicker() else viewModel.closeGallery()
                                        },
                                        pickerMode = viewModel.gallery.pickerMode,
                                        selectedFiles = viewModel.gallery.selectedFiles,
                                        onToggleSelect = { file -> viewModel.toggleGallerySelection(file) },
                                        onConfirmSelection = { coroutineScope.launch { viewModel.confirmGalleryPicker() } },
                                    )

                                    Screen.PlaylistList -> PlaylistsScreen(
                                        playlists = viewModel.playlistEditor.all,
                                        onBack = { viewModel.closePlaylistList() },
                                        onRefresh = { viewModel.refreshPlaylistList() },
                                        onOpenPlaylist = { playlist -> viewModel.openPlaylistForEdit(playlist) },
                                        onPlayPlaylist = { playlist ->
                                            val started = viewModel.playPlaylist(playlist)
                                            if (started) enterFullscreen()
                                            started
                                        },
                                        onCreatePlaylist = { name ->
                                            viewModel.startCreatePlaylist(name)
                                        },
                                        canCreatePlaylist = viewModel::canCreatePlaylist,
                                        listState = playlistListState,
                                    )

                                    Screen.PlaylistEdit -> viewModel.playlistEditor.editing?.let { playlist ->
                                        val addPhotosDirectoryDialogTitle = stringResource(Res.string.playlist_add_photos_directory_dialog_title)
                                        val addMusicDialogTitle = stringResource(Res.string.playlist_add_music_dialog_title)
                                        PlaylistScreen(
                                            playlist = playlist,
                                            lazyListState = playlistEditListState,
                                            onAddMusic = {
                                                val files = chooseMusicFiles(window = window, title = addMusicDialogTitle, extensions = arrayOf("mp3", "m4a", "wav", "ogg", "flac"))
                                                if (files.isNotEmpty()) coroutineScope.launch { viewModel.addSoundtrackToEditingPlaylist(files) }
                                            },
                                            onRemoveSoundtrackItem = viewModel::removeSoundtrackItem,
                                            onAddPhotos = {
                                                chooseDirectory(window = window, title = addPhotosDirectoryDialogTitle)?.let { folder ->
                                                    viewModel.openGalleryForPicking(folder)
                                                }
                                            },
                                            onPlay = {
                                                val started = viewModel.playEditingPlaylist()
                                                if (started) enterFullscreen()
                                                started
                                            },
                                            onBack = { viewModel.closePlaylistEdit() },
                                            onEditName = viewModel::modifyPlaylistName,
                                            canRenamePlaylist = viewModel::canRenamePlaylist,
                                            onModifyDefaultDuration = viewModel::modifyDefaultDuration,
                                            onModifyIsAutomated = viewModel::modifyIsAutomated,
                                            onModifyScrollSpeedLimitPercent = viewModel::modifyScrollSpeedLimitPercent,
                                            onModifySubtitle = viewModel::modifySubtitle,
                                            onModifyTitleZPercent = viewModel::modifyTitleZPercent,
                                            onModifySubtitleZPercent = viewModel::modifySubtitleZPercent,
                                            onModifyTitleStyle = viewModel::modifyTitleStyle,
                                            onModifySubtitleStyle = viewModel::modifySubtitleStyle,
                                            onModifyCredits = viewModel::modifyCredits,
                                            onModifyCreditsZPercent = viewModel::modifyCreditsZPercent,
                                            onModifyCreditsStyle = viewModel::modifyCreditsStyle,
                                            onModifyCreditsPlacement = viewModel::modifyCreditsPlacement,
                                            onReorderPhotos = viewModel::applyPhotosReorder,
                                            onDelete = viewModel::deletePlaylist,
                                            onOpenPlaylistItem = { index -> viewModel.openPlaylistItem(index) },
                                        )
                                    }

                                    Screen.PlaylistItem -> {
                                        val playlist = viewModel.playlistEditor.editing
                                        val index = viewModel.playlistEditor.editingItemIndex
                                        val photo = if (playlist != null && index != null) playlist.photos.getOrNull(index) else null
                                        photo?.let {
                                            PlaylistItemScreen(
                                                photo = it,
                                                isPlaylistManual = playlist != null && !playlist.isAutomated,
                                                onBack = { viewModel.closePlaylistItem() },
                                                onModifyComment = viewModel::modifyItemComment,
                                                onModifyCommentZPercent = viewModel::modifyItemCommentZPercent,
                                                onModifyDuration = viewModel::modifyItemDuration,
                                                onModifyHalfWidth = viewModel::modifyItemHalfWidth,
                                                onModifyMuted = viewModel::modifyItemMuted,
                                                onModifyFullscreenScroll = viewModel::modifyItemFullscreenScroll,
                                                onDelete = viewModel::deletePlaylistItem,
                                            )
                                        }
                                    }

                                    Screen.ImageView -> {
                                    viewModel.playingPlaylist?.let {
                                        PlaylistSoundtrack(
                                            playlist = it,
                                            // keeps playing over the end slide, fading out during its animation
                                            // (so only once its credits, if any, have rolled)
                                            playing = viewModel.playlistSlideKind == PlaylistSlideKind.PHOTO ||
                                                viewModel.playlistSlideKind == PlaylistSlideKind.END,
                                            fadingOut = viewModel.playlistSlideKind == PlaylistSlideKind.END && !viewModel.isCreditsRolling,
                                            mutedForVideo = viewModel.currentPlaylistItem?.let { item -> item.isVideo && !item.isMuted } == true,
                                            audioOutputDeviceId = viewModel.audioOutputDeviceId,
                                        )
                                    }
                                    when (viewModel.playlistSlideKind) {
                                        PlaylistSlideKind.TITLE -> viewModel.playingPlaylist?.let {
                                            if (viewModel.isAutomatedPlaylist) {
                                                LaunchedEffect(viewModel.currentImageIndex) {
                                                    delay(viewModel.slideshowIntervalMs.milliseconds)
                                                    viewModel.advanceSlideshow()
                                                }
                                            }
                                            PlaylistTitleScreen(it, shrinkControls = viewModel.shrinkControls)
                                        }
                                        PlaylistSlideKind.END -> PlaylistEndScreen(
                                            shrinkControls = viewModel.shrinkControls,
                                            creditsPlaylist = viewModel.playingPlaylist.takeIf { viewModel.isCreditsRolling },
                                            onCreditsEnded = viewModel::onCreditsEnded,
                                        )
                                        PlaylistSlideKind.PHOTO, null -> Box(Modifier.fillMaxSize()) { if (viewModel.currentPlaylistItem?.isVideo == true) {
                                            // A video slide auto-advances on natural playback end
                                            // (or loops forever in a manual playlist) instead of the
                                            // fixed-duration timer below - see PlaylistVideoSlide's doc.
                                            viewModel.currentImage?.let { file ->
                                                PlaylistVideoSlide(
                                                    file = file,
                                                    loop = !viewModel.isAutomatedPlaylist,
                                                    halveLeftRightImages = viewModel.halveLeftRightImages,
                                                    isHalfWidth = viewModel.currentPlaylistItem?.isHalfWidth == true,
                                                    isMuted = viewModel.currentPlaylistItem?.isMuted == true,
                                                    shrinkControls = viewModel.shrinkControls,
                                                    audioOutputDeviceId = viewModel.audioOutputDeviceId,
                                                    onAudioOutputDeviceChosen = viewModel::onAudioOutputDeviceChosen,
                                                    onEnded = viewModel::advanceSlideshow,
                                                    onExitFullscreen = exitFullscreen,
                                                    onNextImage = viewModel::showNextImage,
                                                    onPreviousImage = viewModel::showPreviousImage,
                                                )
                                            }
                                        } else {
                                            // "force fullscreen by scrolling" photo: the slide lasts its actual scrolling
                                            // duration, only known once its images are loaded (possibly extended by the
                                            // playlist's scroll speed limit) - see FullscreenScrollStereoImage.
                                            val fullscreenScrollDurationMs = viewModel.playingPlaylist?.fullscreenScrollDurationMs(viewModel.currentPlaylistItem)
                                            var actualScrollDurationMs by remember(viewModel.currentImageIndex, viewModel.imageFiles) { mutableStateOf<Long?>(null) }
                                            if (viewModel.isAutomatedPlaylist) {
                                                LaunchedEffect(viewModel.currentImageIndex, viewModel.imageFiles, actualScrollDurationMs) {
                                                    val slideMs = if (fullscreenScrollDurationMs != null) actualScrollDurationMs ?: return@LaunchedEffect
                                                        else viewModel.slideshowIntervalMs
                                                    delay(slideMs.milliseconds)
                                                    viewModel.advanceSlideshow()
                                                }
                                            }
                                            viewModel.currentImage?.let { file ->
                                                ImageScreen(
                                                    file,
                                                    overrideBitmap = viewModel.photoTools.alignedPreview,
                                                    showInfoPanel = showImageInfoPanel,
                                                    hasAlignedPreview = viewModel.photoTools.alignedPreview != null,
                                                    isAligning = viewModel.isAligning,
                                                    alignToast = viewModel.alignToast,
                                                    saveToast = viewModel.saveToast,
                                                    shareToast = viewModel.share.toast,
                                                    keepBestOfEachOnly = viewModel.keepBestOfEachOnly,
                                                    favoritesOnly = viewModel.favoritesOnly,
                                                    excludeStereoIssues = viewModel.excludeStereoIssues,
                                                    showPhotoFilters = viewModel.playingPlaylist == null,
                                                    showEditTools = viewModel.playingPlaylist == null,
                                                    checkLikelySideBySide = viewModel.playingPlaylist == null,
                                                    halveLeftRightImages = viewModel.halveLeftRightImages,
                                                    fullscreenScrollPhoto = viewModel.currentPlaylistItem.takeIf { fullscreenScrollDurationMs != null },
                                                    fullscreenScrollDurationMs = fullscreenScrollDurationMs,
                                                    scrollSpeedLimitPercent = viewModel.playingPlaylist?.scrollSpeedLimitPercent ?: 0f,
                                                    onFullscreenScrollDurationMs = { actualScrollDurationMs = it },
                                                    shrinkControls = viewModel.shrinkControls,
                                                    manualAlignMode = viewModel.photoTools.manualAlignMode,
                                                    manualAlignOffsetX = viewModel.photoTools.manualAlignOffsetX,
                                                    manualAlignOffsetY = viewModel.photoTools.manualAlignOffsetY,
                                                    manualAlignStepPercent = viewModel.manualAlignStepPercent,
                                                    cropMode = viewModel.photoTools.cropMode,
                                                    cropRect = viewModel.photoTools.cropRect,
                                                    spotIssuesMode = viewModel.photoTools.spotIssuesMode,
                                                    spotIssueRects = viewModel.photoTools.spotIssueRects,
                                                    clickAlignMode = viewModel.photoTools.clickAlignMode,
                                                    clickAlignLeftPoint = viewModel.photoTools.clickAlignLeftPoint,
                                                    clickAlignRightPoint = viewModel.photoTools.clickAlignRightPoint,
                                                    clickAlignPreviewActive = viewModel.photoTools.clickAlignPreviewActive,
                                                    clickAlignPreview = viewModel.photoTools.clickAlignPreview,
                                                    pendingNavigation = viewModel.pendingNavigation,
                                                    onConfirmSaveAlignedAndNavigate = {
                                                        coroutineScope.launch { viewModel.confirmSaveAlignedAndNavigate() }
                                                    },
                                                    onDiscardAlignedPreviewAndNavigate = viewModel::discardAlignedPreviewAndNavigate,
                                                    onCancelPendingNavigation = viewModel::cancelPendingNavigation,
                                                    onKeepBestOfEachOnlyChosen = viewModel::onKeepBestOfEachOnlyChosen,
                                                    onFavoritesOnlyChosen = viewModel::onFavoritesOnlyChosen,
                                                    onExcludeStereoIssuesChosen = viewModel::onExcludeStereoIssuesChosen,
                                                    onHalveLeftRightImagesChosen = viewModel::onHalveLeftRightImagesChosen,
                                                    onShrinkControlsChosen = viewModel::onShrinkControlsChosen,
                                                    onManualAlignStepPercentChosen = viewModel::onManualAlignStepPercentChosen,
                                                    onExitFullscreen = exitFullscreen,
                                                    onNextImage = viewModel::showNextImage,
                                                    onPreviousImage = viewModel::showPreviousImage,
                                                    onToggleInfoPanel = { showImageInfoPanel = !showImageInfoPanel },
                                                    lastShareType = viewModel.share.lastType,
                                                    lastShareDestination = viewModel.share.lastDestination,
                                                    onShareChosen = { type, destination ->
                                                        coroutineScope.launch { viewModel.performShare(type, destination) }
                                                    },
                                                    photoToolActions = PhotoToolActions(
                                                        onAutoAlign = { coroutineScope.launch { viewModel.performAutoAlign(file, AlignKind.HOMOGRAPHY) } },
                                                        onCorrectZoom = { coroutineScope.launch { viewModel.performAutoAlign(file, AlignKind.AFFINE) } },
                                                        onSaveAligned = { coroutineScope.launch { viewModel.performSaveAligned() } },
                                                        onCancelAligned = viewModel::cancelAligned,
                                                        onStartManualAlign = viewModel::startManualAlign,
                                                        onCancelManualAlign = viewModel::cancelManualAlign,
                                                        onSaveManualAlign = { coroutineScope.launch { viewModel.performSaveManualAlign() } },
                                                        onStartCrop = viewModel::startCrop,
                                                        onCropRectFinalized = viewModel::finalizeCropRect,
                                                        onCancelCrop = viewModel::cancelCrop,
                                                        onSaveCrop = { coroutineScope.launch { viewModel.performSaveCrop() } },
                                                        onStartSpotIssues = viewModel::startSpotIssues,
                                                        onSpotIssueRectAdded = viewModel::addSpotIssueRect,
                                                        onCancelSpotIssues = viewModel::cancelSpotIssues,
                                                        onSaveSpotIssues = { coroutineScope.launch { viewModel.performSaveSpotIssues() } },
                                                        onStartClickAlign = viewModel::startClickAlign,
                                                        onClickAlignPointSet = viewModel::setClickAlignPoint,
                                                        onCancelClickAlign = viewModel::cancelClickAlign,
                                                        onSaveClickAlign = { coroutineScope.launch { viewModel.performSaveClickAlign() } },
                                                        onRequestClickAlignPreview = { coroutineScope.launch { viewModel.refreshClickAlignPreview() } },
                                                        onDeleteCurrentImage = { coroutineScope.launch { viewModel.performDeleteCurrentImage() } },
                                                        onDeleteKeepingLeft = { coroutineScope.launch { viewModel.performDeleteCurrentImage(AppViewModel.KeepHalfSide.LEFT) } },
                                                        onDeleteKeepingRight = { coroutineScope.launch { viewModel.performDeleteCurrentImage(AppViewModel.KeepHalfSide.RIGHT) } },
                                                    ),
                                                    onImageLoaded = finishEnteringFullscreen,
                                                    onRequestKeyboardFocus = { focusRequester.requestFocus() },
                                                    onDialogShownChanged = { imageDialogShown = it },
                                                )
                                            }
                                        }
                                        // credits rolling over the last photo, after its normal duration (see AppViewModel.isCreditsRolling)
                                        if (viewModel.isCreditsRolling) viewModel.playingPlaylist?.let {
                                            PlaylistCreditsRoll(it, viewModel.shrinkControls, viewModel::onCreditsEnded)
                                        }
                                        }
                                    }
                                    }

                                    Screen.VideoView -> viewModel.currentImage?.let { file ->
                                        VideoScreen(
                                            file,
                                            halveLeftRightImages = viewModel.halveLeftRightImages,
                                            shrinkControls = viewModel.shrinkControls,
                                            audioOutputDeviceId = viewModel.audioOutputDeviceId,
                                            onAudioOutputDeviceChosen = viewModel::onAudioOutputDeviceChosen,
                                            onExitFullscreen = exitFullscreen,
                                        )
                                    }
                                }

                                if (isEnteringFullscreen && inViewer) {
                                    FullscreenLoadingOverlay()
                                }

                                if (viewModel.playlistEditor.isCopying) {
                                    CopyingFilesOverlay()
                                }

                                if (viewModel.showVlcMissingSheet) {
                                    VlcMissingSheet(onDismiss = viewModel::dismissVlcMissingSheet)
                                }

                                whatsNewRelease?.let { release ->
                                    WhatsNewDialog(release, onDismiss = {
                                        whatsNewRelease = null
                                        Analytics.logEvent("whats_new_dismissed")
                                    })
                                }

                                if (!analyticsConsentAnswered) {
                                    AnalyticsConsentDialog(onAnswered = { granted ->
                                        Analytics.setConsent(granted)
                                        analyticsConsentAnswered = true
                                        if (granted) Analytics.logEvent("app_launch")
                                    })
                                }

                                if (showCrashRecoveredDialog) {
                                    CrashRecoveredDialog(onDismiss = { showCrashRecoveredDialog = false })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
