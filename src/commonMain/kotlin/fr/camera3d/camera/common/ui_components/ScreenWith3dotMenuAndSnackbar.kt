package fr.camera3d.camera.common.ui_components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A mouse-oriented vertical scrollbar, drawn instead of the touch-oriented [ScrollDownHint] by
 * [ScreenWith3dotMenuAndSnackbar] and inside PortableOkCancelDialog's scrollable area.
 * @param thickness : width reserved at the end of the scrolled content so the scrollbar doesn't cover it
 * @param scrollbar : draws the scrollbar for the given state; only called once the content overflows
 */
class MouseScrollbar(
    val thickness: Dp,
    val scrollbar: @Composable (scrollState: ScrollState, modifier: Modifier) -> Unit,
)

/**
 * Null (the default, used by the Android app) keeps the bouncing chevron hint. The desktop app
 * provides its Windows-style scrollbar here - Compose's VerticalScrollbar only exists on desktop,
 * so this shared file can't call it directly.
 */
val LocalMouseScrollbar = staticCompositionLocalOf<MouseScrollbar?> { null }

/**
 * @param screenTitle
 * @param navigationIcon : the possible Home icon (usually a back arrow)
 * @param actionsContent : a Composable with the three dot icon + its DropdownMenu()
 * @param bottomBar : a BottomAppBar composable to be used as bottom bar
 * @param snackbarHostState : state for snackbar handling; snackbar is animated (slide up/down)
 * @param scrollable : when true (default) content is placed in a scrollable Column with 8dp padding;
 *   when false content is placed in a Box that fills the available space with no extra padding.
 * @param screenContent : one or more @Composable that will be displayed in a scrollable pane
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenWith3dotMenuAndSnackbar(screenTitle : String,
                                  navigationIcon : @Composable ()->Unit = {},
                                  actionsContent : @Composable () -> Unit,
                                  bottomBar : @Composable () -> Unit,
                                  floatingActionButton: @Composable () -> Unit = {},
                                  snackbarHostState : SnackbarHostState,
                                  scrollable: Boolean = true,
                                  scrollState: ScrollState = rememberScrollState(),
                                  screenContent : @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle, color = MaterialTheme.colorScheme.onPrimary) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary), // Change the color here!
                navigationIcon = {
                    navigationIcon()
                },
                actions = {
                    actionsContent()
                }
            )
        },
        bottomBar = { bottomBar() },
        floatingActionButton = floatingActionButton,
        snackbarHost = { AnimatedSnackbarHost(snackbarHostState) },
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
    ) { innerPadding ->
        if (scrollable) {
            val mouseScrollbar = LocalMouseScrollbar.current
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Column(modifier = Modifier.fillMaxSize().padding(start=8.dp, end=8.dp + (mouseScrollbar?.thickness ?: 0.dp), top=8.dp)
                    .verticalScroll(scrollState),
                ) {
                    screenContent()
                }
                if (mouseScrollbar != null) {
                    if (scrollState.maxValue > 0) {
                        mouseScrollbar.scrollbar(scrollState, Modifier.align(Alignment.CenterEnd))
                    }
                } else if (scrollState.canScrollForward) {
                    ScrollDownHint(modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                screenContent()
            }
        }
    }
}

/**
 * Fade + bouncing chevron shown at the bottom of a scrollable screen while there is
 * more content below, so it's obvious the screen should be scrolled (e.g. large font sizes
 * pushing call-to-action buttons out of the initially visible area).
 */
@Composable
private fun ScrollDownHint(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "scrollDownHint")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scrollDownHintOffset"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0f), MaterialTheme.colorScheme.background)
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardDoubleArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(28.dp)
                .padding(bottom = (offsetY).dp)
        )
    }
}