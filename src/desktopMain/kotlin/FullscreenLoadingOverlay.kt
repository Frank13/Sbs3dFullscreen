import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.copying_files_message
import sbs3dViewer.resources.opening_fullscreen_message

/**
 * Shown while entering fullscreen (see Main.kt's isEnteringFullscreen), covering whatever's
 * being loaded underneath (the first photo's decode, a video's first frame). A single centered
 * spinner would sit at the screen's horizontal center - the boundary between the two eye halves,
 * not the middle of either one - so, like every other overlay in this codebase, it's duplicated
 * per half and each copy is centered within its own half.
 */
@Composable
fun FullscreenLoadingOverlay() {
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.fillMaxSize().weight(1f)) { FullscreenLoadingHalf() }
        Box(Modifier.fillMaxSize().weight(1f)) { FullscreenLoadingHalf() }
    }
}

/**
 * Shown over the (non-fullscreen) playlist screens while PlaylistEditorState copies files in the
 * background (see its isCopying). A plain 2D window here, so a single centered spinner - unlike
 * [FullscreenLoadingOverlay]'s per-half copies. Swallows clicks so the playlist being copied into
 * can't be closed or edited until the copy finishes.
 */
@Composable
fun CopyingFilesOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(Res.string.copying_files_message), color = Color.White, fontSize = 18.sp)
        }
    }
}

@Composable
private fun FullscreenLoadingHalf() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(Res.string.opening_fullscreen_message),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    shadow = Shadow(color = Color.Black, blurRadius = 3f, offset = Offset(2f, 2f)),
                ),
            )
        }
    }
}
