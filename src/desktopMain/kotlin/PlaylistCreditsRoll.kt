import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.ui.ComposableCreditsRollStereo
import fr.camera3d.camera.feature_playlists.ui.creditsSpeedPercent
import kotlinx.coroutines.delay

/**
 * The playlist credits rolling in stereo (shared ComposableCreditsRollStereo, each eye-half shifted by
 * Playlist.creditsZPercent), over the last photo or on the end slide - see AppViewModel.isCreditsRolling.
 * [onEnded] is called once every view (each eye, each squeezed copy) has completely rolled. Under
 * [shrinkControls] the credits are squeezed by 2 per eye-half like the title slide (see [SqueezedPerEyeHalf]).
 */
@Composable
fun PlaylistCreditsRoll(playlist: Playlist, shrinkControls: Boolean, onEnded: () -> Unit) {
    val currentOnEnded by rememberUpdatedState(onEnded)
    // end of the roll, as reported by the views: the credits end when the longest one has completely rolled
    var endAtMs by remember { mutableLongStateOf(0L) }
    SqueezedPerEyeHalf(active = shrinkControls) {
        ComposableCreditsRollStereo(
            credits = playlist.credits,
            style = playlist.creditsStyle,
            zPercent = playlist.creditsZPercent,
            speedPercent = playlist.creditsSpeedPercent(),
            isPaused = false,
            onRemainingMs = { remainingMs ->
                val viewEndAtMs = System.currentTimeMillis() + remainingMs
                if (viewEndAtMs > endAtMs) endAtMs = viewEndAtMs
            },
        )
    }
    if (endAtMs > 0L) {
        LaunchedEffect(endAtMs) {
            delay((endAtMs - System.currentTimeMillis()).coerceAtLeast(0L))
            currentOnEnded()
        }
    }
}
