import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.image_settings_audio_output_label
import sbs3dViewer.resources.image_settings_exit_fullscreen_label
import sbs3dViewer.resources.image_settings_next_label
import sbs3dViewer.resources.image_settings_previous_label
import java.io.File

// Same sign convention as InfoPanelShiftPercent/CursorShiftPercent (negative = toward the
// viewer): -1% makes the controls read as floating just in front of the screen.
private const val ProgressBarShiftPercent = -0.01f
private val ProgressBarHeight = 4.dp
// Taller than the visual track so the handle is easy to grab - same "bigger hit area than visual
// mark" idea as CursorClickSlop.
private val ProgressBarTouchHeight = 20.dp
private val ProgressHandleRadius = 6.dp
private val ProgressBarBottomPadding = 40.dp
private val ProgressBarHorizontalPadding = 60.dp
private val PlayPauseButtonSize = 32.dp

// Same rationale as ImageScreen's SettingsMenuShiftPercent - UI chrome, not part of the video
// itself, so it reads as pinned to the screen glass rather than floating.
private const val VideoSettingsMenuShiftPercent = 0f

/**
 * Mutable playback state backing [VideoScreen]/[PlaylistVideoSlide], built by [rememberVideoPlayerState].
 * A plain `by mutableStateOf`-backed holder (not a data class) so its identity stays stable across
 * recomposition while individual fields still trigger recomposition on change, same idea as e.g.
 * AppViewModel's PhotoTools holder.
 */
private class VideoPlayerState {
    var frameBitmap by mutableStateOf<ImageBitmap?>(null)

    // Fraction [0,1] of playback elapsed, or null before the first positionChanged event - in
    // which case the progress bar just stays hidden.
    var progress by mutableStateOf<Float?>(null)

    // Live scrub position while the handle is being dragged, overriding `progress` for display
    // only - the actual seek is requested once on release (onScrubEnd), not on every drag frame.
    var dragProgress by mutableStateOf<Float?>(null)
    var paused by mutableStateOf(false)
    var mediaPlayer: VlcPlayer? = null

    // The mmdevice (Windows) devices this player can send audio to, enumerated once from the
    // factory at setup - what the "Audio output" picker lists. Includes libVLC's own "default"
    // entry (empty deviceId, localized long name) so the user can hand control back to Windows.
    var audioDevices: List<AudioDevice> = emptyList()

    val onTogglePause: () -> Unit = {
        val newPaused = !paused
        paused = newPaused
        mediaPlayer?.setPause(newPaused)
    }
    val onScrub: (Float) -> Unit = { fraction -> dragProgress = fraction }
    val onScrubEnd: (Float) -> Unit = { fraction ->
        dragProgress = null
        mediaPlayer?.setPosition(fraction)
    }
}

/**
 * Sets up real libVLC (through Vlc.kt's own JNA binding) playback of [file] - the same engine the
 * standalone VLC app uses - hardware-decoded and frame-paced by libVLC itself, unlike the earlier
 * FFmpegFrameGrabber-based pipeline (software decode with a hand-rolled, drift-prone frame clock)
 * which still looked choppy even after forcing hardware decoder names. Requires VLC to be
 * installed on the machine - see Vlc.kt for where it's looked up.
 * Rendering stays headless (no AWT/Swing video surface): libVLC's video callbacks write each
 * decoded frame into native memory, which Vlc.kt copies into a BufferedImage that is then handed
 * to Compose. No manual audio pipeline either - libVLC plays the audio track itself through its own
 * output, to the Windows default playback device unless [audioOutputDeviceId] pins a specific
 * mmdevice one (see AppViewModel.audioOutputDeviceId for why that's needed). [repeat] controls
 * whether playback loops forever (standalone [VideoScreen]) or plays once and calls [onEnded] (a
 * [PlaylistVideoSlide] advancing an automated slideshow) - [onEnded] is deliberately not invoked
 * while [repeat] is true, since a looping video calling back into slideshow-advance logic on
 * every lap would be wrong.
 */
@Composable
private fun rememberVideoPlayerState(file: File, repeat: Boolean, audioOutputDeviceId: String, isMuted: Boolean = false, onEnded: () -> Unit = {}): VideoPlayerState {
    val state = remember(file) { VideoPlayerState() }

    DisposableEffect(file) {
        val factory = VlcInstance()
        val player = factory.newPlayer()
        state.audioDevices = factory.audioDevices()
        // Before play(): with a module name, libVLC stores this as the "mmdevice-audio-device"
        // variable the output reads when it's created on the first decoded audio frame. Only a
        // non-empty id is pinned - "" means leave the default alone (see the LaunchedEffect below
        // for the mid-playback path, which handles "" itself).
        if (audioOutputDeviceId.isNotEmpty()) player.setOutputDevice("mmdevice", audioOutputDeviceId)
        // Mirrors CameraSync3D's per-item "muted" flag (PlaylistItem.isMuted): silences this
        // video's own audio track without touching the Windows output device/volume. Done with the
        // ":no-audio" media option (see play() below), not setMute: libVLC's mute is process-wide
        // on Windows and would also silence the playlist soundtrack.

        // Each decoded frame arrives as a BufferedImage reused by the player, so it's converted to
        // an ImageBitmap (a copy) right here.
        player.setFrameListener { image -> state.frameBitmap = image.toComposeImageBitmap() }
        player.setRepeat(repeat)
        player.onPositionChanged = { newPosition -> state.progress = newPosition.coerceIn(0f, 1f) }
        player.onFinished = { if (!repeat) onEnded() }
        state.mediaPlayer = player
        player.play(file.absolutePath, noAudio = isMuted)

        onDispose {
            state.mediaPlayer = null
            player.release()
            factory.release()
        }
    }

    // Mid-playback switch when the user picks a device in the "Audio output" dialog, so it takes
    // effect audibly right away rather than only from the next video. A null module means "apply
    // to the live audio output" (libVLC's aout_DeviceSet), which also accepts "" to go back to
    // the Windows default - it's just a no-op while no output exists yet (e.g. on this effect's
    // very first run right after play(), where the pre-play path above already covers it). Runs
    // on the Compose thread, not libVLC's event thread, so no MediaPlayer.submit() is needed.
    LaunchedEffect(state, audioOutputDeviceId) {
        state.mediaPlayer?.setOutputDevice(null, audioOutputDeviceId)
    }

    return state
}

@Composable
private fun VideoPlayerSurface(
    state: VideoPlayerState,
    halveLeftRightImages: Boolean,
    isHalfWidth: Boolean,
    isMuted: Boolean,
    shrinkControls: Boolean,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
    onExitFullscreen: () -> Unit,
    onNextImage: (() -> Unit)? = null,
    onPreviousImage: (() -> Unit)? = null,
) {
    Stereo3DCursorHost(shrinkControls = shrinkControls) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val halfWidthDp = maxWidth / 2
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                state.frameBitmap?.let { bitmap ->
                    VideoFrame(bitmap, halveLeftRightImages, isHalfWidth)
                }
            }
            if (LocalCursorVisible.current) {
                StereoVideoControls(
                    state.paused, state.onTogglePause, state.progress, state.dragProgress,
                    state.onScrub, state.onScrubEnd, halfWidthDp, shrinkControls, isMuted,
                )
            }
            VideoSettingsMenuOverlay(
                shrinkControls, onExitFullscreen, onNextImage, onPreviousImage,
                audioDevices = state.audioDevices,
                audioOutputDeviceId = audioOutputDeviceId,
                onAudioOutputDeviceChosen = onAudioOutputDeviceChosen,
            )
        }
    }
}

/**
 * Standalone video playback (file-association double-click, or a video picked directly) - always
 * loops. There's no per-item [isHalfWidth][PlaylistItem.isHalfWidth] flag outside a playlist (a
 * directly-opened file is always treated as normal/full-width, same convention as ImageScreen's
 * standalone photo viewing), so only [halveLeftRightImages] (the output monitor's own squeeze
 * requirement) can apply here.
 */
@Composable
fun VideoScreen(
    file: File,
    halveLeftRightImages: Boolean,
    shrinkControls: Boolean,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
    onExitFullscreen: () -> Unit,
) {
    VideoPlayerSurface(
        rememberVideoPlayerState(file, repeat = true, audioOutputDeviceId = audioOutputDeviceId),
        halveLeftRightImages = halveLeftRightImages,
        isHalfWidth = false,
        isMuted = false,
        shrinkControls = shrinkControls,
        audioOutputDeviceId = audioOutputDeviceId,
        onAudioOutputDeviceChosen = onAudioOutputDeviceChosen,
        onExitFullscreen = onExitFullscreen,
    )
}

/**
 * A video mid-playlist (Playlist Show).
 * [loop] plays the video on repeat (a manual/non-automated playlist, since nothing else would ever
 * advance past it); when false, [onEnded] fires once playback naturally finishes so the caller can
 * advance the slideshow, mirroring CameraSync3D's confirmed SlideshowViewModel behavior. Unlike
 * standalone [VideoScreen], this also gets the settings menu's Next/Previous rows (see
 * [VideoSettingsMenuOverlay]) - ImageScreen's photo slides already have them, so a video slide
 * should let the user skip past it with the mouse too, not just the keyboard.
 *
 * [halveLeftRightImages] (output: does the monitor need a squeezed frame) and [isHalfWidth] (input:
 * is this particular item's source already squeezed) are independent - see [VideoFrame].
 */
@Composable
fun PlaylistVideoSlide(
    file: File,
    loop: Boolean,
    halveLeftRightImages: Boolean,
    isHalfWidth: Boolean,
    isMuted: Boolean,
    shrinkControls: Boolean,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
    onEnded: () -> Unit,
    onExitFullscreen: () -> Unit,
    onNextImage: () -> Unit,
    onPreviousImage: () -> Unit,
) {
    VideoPlayerSurface(
        rememberVideoPlayerState(file, repeat = loop, audioOutputDeviceId = audioOutputDeviceId, isMuted = isMuted, onEnded = onEnded),
        halveLeftRightImages = halveLeftRightImages,
        isHalfWidth = isHalfWidth,
        isMuted = isMuted,
        shrinkControls = shrinkControls,
        audioOutputDeviceId = audioOutputDeviceId,
        onAudioOutputDeviceChosen = onAudioOutputDeviceChosen,
        onExitFullscreen = onExitFullscreen,
        onNextImage = onNextImage,
        onPreviousImage = onPreviousImage,
    )
}

/**
 * Crops the combined L+R video frame apart and draws each eye-half fit and centered within its own
 * half of the window, same split as ImageScreen's `StereoImage` (see that doc for why splitting per
 * half - rather than fitting the combined bitmap as one unit - keeps the L/R split centered on each
 * half's own midpoint). A single unsplit fit (the previous behavior here) is only correct when
 * neither factor below rescales anything; once either does, the combined frame must be cropped in
 * half first, or the "shrunk" content ends up as one squeezed block with black bars down the
 * middle, half of it being the wrong eye's content bleeding into the other half's box.
 */
@Composable
private fun VideoFrame(bitmap: ImageBitmap, halveLeftRightImages: Boolean, isHalfWidth: Boolean) {
    val halfWidth = bitmap.width / 2
    Row(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            VideoFrameHalf(bitmap, IntOffset(0, 0), IntSize(halfWidth, bitmap.height), halveLeftRightImages, isHalfWidth)
        }
        Box(Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
            VideoFrameHalf(bitmap, IntOffset(halfWidth, 0), IntSize(halfWidth, bitmap.height), halveLeftRightImages, isHalfWidth)
        }
    }
}

/**
 * Draws one eye-half ([srcOffset]/[srcSize] crop of [bitmap]) fit-and-centered in its own box, same
 * "virtual effective width" trick as ImageScreen's `StereoHalfImage`, generalized to combine two
 * independent factors instead of one:
 *
 * - [isHalfWidth] (input): this item's source frame is itself squeezed/half-width and needs a 2x
 *   horizontal stretch to look right - see [PlaylistItem.isHalfWidth].
 * - [halveLeftRightImages] (output): the monitor wants a 2x-squeezed frame (its own hardware
 *   unsqueezes per eye) - see `AppViewModel.halveLeftRightImages`.
 *
 * The two stack multiplicatively rather than needing separate cases: an already-squeezed source
 * fed to a monitor that also wants squeezed input needs no further scaling at all (the factors
 * cancel out), which falls out naturally from multiplying them instead of branching on every
 * combination.
 */
@Composable
private fun VideoFrameHalf(bitmap: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, halveLeftRightImages: Boolean, isHalfWidth: Boolean) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val inputFactor = if (isHalfWidth) 2f else 1f
        val outputFactor = if (halveLeftRightImages) 0.5f else 1f
        val effectiveWidthPx = srcSize.width * inputFactor * outputFactor
        val scale = minOf(size.width / effectiveWidthPx, size.height / srcSize.height)
        val dstWidth = effectiveWidthPx * scale
        val dstHeight = srcSize.height * scale
        drawImage(
            image = bitmap,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset(((size.width - dstWidth) / 2).toInt(), ((size.height - dstHeight) / 2).toInt()),
            dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * Play/pause button plus playback-position bar, duplicated on both halves and offset by
 * [ProgressBarShiftPercent] so they read correctly in 3D - same left/right-duplication technique
 * as InfoPanel/Stereo3DCursorHost. Only shown while [LocalCursorVisible] is true, i.e. in sync
 * with the auto-hiding 3D cursor. Under [shrinkControls] the bar is squeezed by 2 around its own
 * center (see ShrinkControls.kt) - centered rather than start-anchored like the settings gear,
 * since it's a centered full-width element, so it stays centered once the monitor unsqueezes it.
 */
@Composable
private fun StereoVideoControls(
    paused: Boolean,
    onTogglePause: () -> Unit,
    progress: Float?,
    dragProgress: Float?,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    halfWidthDp: Dp,
    shrinkControls: Boolean,
    isMuted: Boolean,
) {
    val shift = halfWidthDp * ProgressBarShiftPercent
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().weight(1f)) {
            VideoControlsHalf(paused, onTogglePause, progress, dragProgress, onScrub, onScrubEnd, shrinkControls, isMuted, offsetX = -shift / 2)
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            VideoControlsHalf(paused, onTogglePause, progress, dragProgress, onScrub, onScrubEnd, shrinkControls, isMuted, offsetX = shift / 2)
        }
    }
}

@Composable
private fun VideoControlsHalf(
    paused: Boolean,
    onTogglePause: () -> Unit,
    progress: Float?,
    dragProgress: Float?,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    shrinkControls: Boolean,
    isMuted: Boolean,
    offsetX: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset(x = offsetX)
            .padding(horizontal = ProgressBarHorizontalPadding)
            .padding(bottom = ProgressBarBottomPadding),
        contentAlignment = Alignment.BottomStart
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shrinkHorizontally(shrinkControls, TransformOrigin.Center)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(12.dp)
                .cursor3DDepthTarget(ProgressBarShiftPercent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // canFocus = false for the same reason as Exif3dInfoPanel's icons: a click stealing
            // keyboard focus would break Escape/arrow key handling on Main.kt's root Box.
            Box(
                modifier = Modifier
                    .size(PlayPauseButtonSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .focusProperties { canFocus = false }
                    .clickable(onClick = onTogglePause)
                    .cursor3DClickTarget(onTogglePause),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isMuted) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (progress != null) {
                Spacer(Modifier.width(12.dp))
                // dragProgress overrides progress while the handle is being dragged, so the bar
                // follows the pointer even though the actual seek only fires on release
                // (onScrubEnd) - see VideoScreen's onScrub/onScrubEnd.
                val displayedProgress = (dragProgress ?: progress).coerceIn(0f, 1f)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .height(ProgressBarTouchHeight)
                        .cursor3DScrubTarget(onScrub = onScrub, onScrubEnd = onScrubEnd),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ProgressBarHeight)
                            .clip(RoundedCornerShape(ProgressBarHeight / 2))
                            .background(Color.White.copy(alpha = 0.3f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(displayedProgress)
                                .clip(RoundedCornerShape(ProgressBarHeight / 2))
                                .background(Color.White.copy(alpha = 0.9f))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .offset(x = maxWidth * displayedProgress - ProgressHandleRadius)
                            .size(ProgressHandleRadius * 2)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

/**
 * Settings gear at the top start of each half, built from the same [SettingsMenuGear]/
 * [SettingsMenuPanel]/[SettingsMenuItemRow] pieces ImageScreen's settings menu uses - previously
 * videos had no way to reach Exit fullscreen/Next/Previous except the keyboard. Trimmed down to
 * the items that make sense for a video (no crop/align/share/info panel - those are photo-editing
 * tools with no video equivalent). [onNextImage]/[onPreviousImage] are null for standalone
 * [VideoScreen] playback (no multi-video browsing today), which hides those rows.
 *
 * The "Audio output" row expands in place into a submenu of [audioDevices] as radio rows (their
 * VLC-localized long names, including libVLC's own "default" entry whose empty id hands control
 * back to the Windows default playback device) - picking one applies immediately, no OK step.
 */
@Composable
private fun VideoSettingsMenuOverlay(
    shrinkControls: Boolean,
    onExitFullscreen: () -> Unit,
    onNextImage: (() -> Unit)?,
    onPreviousImage: (() -> Unit)?,
    audioDevices: List<AudioDevice>,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var audioOutputExpanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val halfWidth = maxWidth / 2
        val shift = halfWidth * VideoSettingsMenuShiftPercent
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                VideoSettingsMenuHalf(offsetX = -shift / 2, expanded, { expanded = !expanded }, shrinkControls, onExitFullscreen, onNextImage, onPreviousImage, audioOutputExpanded, { audioOutputExpanded = !audioOutputExpanded }, audioDevices, audioOutputDeviceId, onAudioOutputDeviceChosen)
            }
            Box(Modifier.fillMaxSize().weight(1f)) {
                VideoSettingsMenuHalf(offsetX = shift / 2, expanded, { expanded = !expanded }, shrinkControls, onExitFullscreen, onNextImage, onPreviousImage, audioOutputExpanded, { audioOutputExpanded = !audioOutputExpanded }, audioDevices, audioOutputDeviceId, onAudioOutputDeviceChosen)
            }
        }
    }
}

@Composable
private fun VideoSettingsMenuHalf(
    offsetX: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    shrinkControls: Boolean,
    onExitFullscreen: () -> Unit,
    onNextImage: (() -> Unit)?,
    onPreviousImage: (() -> Unit)?,
    audioOutputExpanded: Boolean,
    onToggleAudioOutputExpanded: () -> Unit,
    audioDevices: List<AudioDevice>,
    audioOutputDeviceId: String,
    onAudioOutputDeviceChosen: (String) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(start = 24.dp, top = 24.dp).offset(x = offsetX),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
            SettingsMenuGear(shrinkControls, VideoSettingsMenuShiftPercent, onToggleExpanded)
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                SettingsMenuPanel(shrinkControls, VideoSettingsMenuShiftPercent) {
                    if (onNextImage != null) {
                        SettingsMenuItemRow(stringResource(Res.string.image_settings_next_label), shrinkControls) { trackMenuItem("video_next"); onNextImage() }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (onPreviousImage != null) {
                        SettingsMenuItemRow(stringResource(Res.string.image_settings_previous_label), shrinkControls) { trackMenuItem("video_previous"); onPreviousImage() }
                        Spacer(Modifier.height(8.dp))
                    }
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_audio_output_label), shrinkControls) { trackMenuItem("video_audio_output"); onToggleAudioOutputExpanded() }
                    if (audioOutputExpanded) {
                        audioDevices.forEach { device ->
                            SettingsMenuRadioRow(device.longName, selected = audioOutputDeviceId == device.deviceId, shrinkControls) {
                                trackMenuItem("video_audio_output_device")
                                onAudioOutputDeviceChosen(device.deviceId)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SettingsMenuItemRow(stringResource(Res.string.image_settings_exit_fullscreen_label), shrinkControls) { trackMenuItem("video_exit_fullscreen"); onExitFullscreen() }
                }
            }
        }
    }
}
