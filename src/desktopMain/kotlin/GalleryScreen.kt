import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.camera3d.camera.common.ui_components.MouseScrollbar
import fr.camera3d.camera.common.ui_components.ScreenWith3dotMenuAndSnackbar
import fr.camera3d.camera.feature_playlists.domain.isVideoFilename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import sbs3dViewer.resources.Res
import sbs3dViewer.resources.gallery_empty
import sbs3dViewer.resources.gallery_favorite_content_description
import sbs3dViewer.resources.gallery_image_count
import sbs3dViewer.resources.gallery_legend_image_content_description
import sbs3dViewer.resources.gallery_legend_text_content_description
import sbs3dViewer.resources.gallery_picker_confirm_button
import sbs3dViewer.resources.gallery_picker_selected_count
import sbs3dViewer.resources.gallery_screen_title
import sbs3dViewer.resources.gallery_warning_content_description
import sbs3dViewer.resources.ic_image_comment
import sbs3dViewer.resources.ic_text_comment
import sbs3dViewer.resources.playlist_back_button
import java.awt.image.BufferedImage
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import java.awt.Image as AwtImage

// Wide rather than square: these are side-by-side 3D photos, so a wide thumbnail box shows
// both eye-halves instead of cropping most of the frame away.
private val thumbnailWidth = 320.dp
private val thumbnailHeight = 160.dp
private val thumbnailSpacing = 8.dp

// Mimics the classic Windows scrollbar (flat rectangular thumb, ~17px wide, light-gray track)
// instead of Compose Desktop's default thin/rounded/Mac-like style. Also used by WelcomeScreen's
// settings dialog.
internal val windowsScrollbarTrackColor = Color(0xFFF0F0F0)
internal val windowsScrollbarStyle = ScrollbarStyle(
    minimalHeight = 16.dp,
    thickness = 17.dp,
    shape = RectangleShape,
    hoverDurationMillis = 300,
    unhoverColor = Color(0xFFC1C1C1),
    hoverColor = Color(0xFFA6A6A6),
)

/** The same scrollbar, provided by AppTheme to the screens/dialogs shared with the Android app. */
internal val windowsMouseScrollbar = MouseScrollbar(windowsScrollbarStyle.thickness) { scrollState, modifier ->
    WindowsVerticalScrollbar(adapter = rememberScrollbarAdapter(scrollState), modifier = modifier)
}

/** windowsScrollbarStyle'd vertical scrollbar on its light-gray track, for this app's own scrolling lists. */
@Composable
internal fun WindowsVerticalScrollbar(adapter: ScrollbarAdapter, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(windowsScrollbarStyle.thickness)
            .background(windowsScrollbarTrackColor),
    ) {
        CompositionLocalProvider(LocalScrollbarStyle provides windowsScrollbarStyle) {
            VerticalScrollbar(adapter = adapter, modifier = Modifier.fillMaxHeight())
        }
    }
}

// Decoded at ~2x the on-screen thumbnail size so it still looks sharp on hi-DPI displays.
private const val thumbnailPixelWidth = 640
private const val thumbnailPixelHeight = 320

// How many rows beyond the visible window (above and below) to eagerly decode into ThumbnailCache.
private const val thumbnailPrefetchRowLookahead = 4

/**
 * Shows the photos and videos found under a directory chosen from WelcomeScreen's "Open 3D image
 * directory" button, recursively grouped by subdirectory (each a collapsible section -
 * AppViewModel.openGallery/scanGalleryDirectory). Tapping a thumbnail opens ImageView/VideoView on
 * that subdirectory's same-type files, starting at the tapped one - see AppViewModel.openGalleryImage.
 * Unlike CameraSync3D's GalleryFragment (MediaStore-backed, flat list, Coil thumbnails), this reads
 * plain files straight off disk and decodes thumbnails on the fly - there's no Android gallery
 * equivalent for the per-subdirectory grouping, since the whole notion of "browse a folder tree"
 * doesn't exist on a MediaStore-backed gallery.
 *
 * Also doubles as a multi-select photo/video picker (see the pickerMode parameters below) when
 * opened from PlaylistEdit's "+" button - see AppViewModel.openGalleryForPicking.
 */
/**
 * One entry per item the LazyColumn built by GalleryScreen below actually lays down: null for a
 * group header, or that row's files for a photo row (one row item per [columns] photos, only for
 * expanded groups). Building this once and indexing into it - rather than re-deriving row/header
 * positions separately - is what lets [flatItemIndexOf] and the scroll-ahead thumbnail prefetch
 * agree on exactly which LazyColumn item index holds which files.
 */
private fun buildFlatRows(groups: List<GalleryGroup>, expandedGroups: Set<String>, columns: Int): List<List<File>?> =
    buildList {
        groups.forEach { group ->
            add(null) // header item
            if (expandedGroups.contains(group.relativePath)) {
                group.files.chunked(columns).forEach { add(it) }
            }
        }
    }

/**
 * Absolute position (item index within [flatRows]) of the row containing [target], or null if
 * [target] isn't in any expanded group. Feeds LazyListState.scrollToItem(index) to land on the
 * right row.
 */
private fun flatItemIndexOf(flatRows: List<List<File>?>, target: File): Int? =
    flatRows.indexOfFirst { row -> row?.contains(target) == true }.takeIf { it >= 0 }

@Composable
fun GalleryScreen(
    groups: List<GalleryGroup>,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onOpenImage: (GalleryGroup, Int) -> Unit,
    onBack: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    scrollTarget: File? = null,
    onScrollTargetConsumed: () -> Unit = {},
    pickerMode: Boolean = false,
    selectedFiles: Set<File> = emptySet(),
    onToggleSelect: (File) -> Unit = {},
    onConfirmSelection: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    ScreenWith3dotMenuAndSnackbar(
        screenTitle = if (pickerMode) {
            stringResource(Res.string.gallery_picker_selected_count, selectedFiles.size)
        } else {
            stringResource(Res.string.gallery_screen_title)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.playlist_back_button),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        actionsContent = {
            if (pickerMode) {
                TextButton(
                    onClick = onConfirmSelection,
                    enabled = selectedFiles.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.38f),
                    ),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(Res.string.gallery_picker_confirm_button))
                }
            }
        },
        bottomBar = {},
        snackbarHostState = snackbarHostState,
        scrollable = false,
        screenContent = {
            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(Res.string.gallery_empty))
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = (maxWidth / (thumbnailWidth + thumbnailSpacing)).toInt().coerceAtLeast(1)
                    val flatRows = remember(groups, expandedGroups, columns) { buildFlatRows(groups, expandedGroups, columns) }

                    LaunchedEffect(scrollTarget, flatRows) {
                        val target = scrollTarget ?: return@LaunchedEffect
                        val itemIndex = flatItemIndexOf(flatRows, target)
                        if (itemIndex != null) listState.scrollToItem(itemIndex)
                        onScrollTargetConsumed()
                    }

                    // Warms ThumbnailCache for rows just outside the visible window (in both
                    // directions, since the user may scroll either way) so their decode is already
                    // done - or in flight - by the time GalleryThumbnail actually composes them.
                    LaunchedEffect(flatRows) {
                        snapshotFlow {
                            val visible = listState.layoutInfo.visibleItemsInfo
                            (visible.firstOrNull()?.index ?: 0) to (visible.lastOrNull()?.index ?: 0)
                        }.distinctUntilChanged().collect { (firstVisible, lastVisible) ->
                            val from = (firstVisible - thumbnailPrefetchRowLookahead).coerceAtLeast(0)
                            val to = (lastVisible + thumbnailPrefetchRowLookahead).coerceAtMost(flatRows.lastIndex)
                            for (i in from..to) {
                                // Video frames aren't decoded through ThumbnailCache (see GalleryThumbnail) -
                                // only prefetch the photos in this row.
                                flatRows.getOrNull(i)?.forEach { file -> if (!isVideoFilename(file.name)) ThumbnailCache.warm(file) }
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(thumbnailSpacing),
                        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp + windowsScrollbarStyle.thickness, bottom = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        groups.forEach { group ->
                            item(key = "header:${group.relativePath}") {
                                GalleryGroupHeader(group, expandedGroups.contains(group.relativePath), onToggleGroup)
                            }
                            if (expandedGroups.contains(group.relativePath)) {
                                val rows = group.files.chunked(columns)
                                itemsIndexed(rows, key = { rowIndex, _ -> "row:${group.relativePath}:$rowIndex" }) { rowIndex, rowFiles ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(thumbnailSpacing)) {
                                        rowFiles.forEachIndexed { columnIndex, file ->
                                            val index = rowIndex * columns + columnIndex
                                            GalleryThumbnail(
                                                file = file,
                                                pickerMode = pickerMode,
                                                selected = file in selectedFiles,
                                                onClick = {
                                                    if (pickerMode) onToggleSelect(file) else onOpenImage(group, index)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(windowsScrollbarStyle.thickness)
                            .background(windowsScrollbarTrackColor),
                    ) {
                        CompositionLocalProvider(LocalScrollbarStyle provides windowsScrollbarStyle) {
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(listState),
                                modifier = Modifier.fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun GalleryGroupHeader(group: GalleryGroup, expanded: Boolean, onToggle: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(group.relativePath) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text(group.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(stringResource(Res.string.gallery_image_count, group.files.size))
    }
}

/**
 * The kind of legend a photo carries, mirroring CameraSync3D's LegendIconType. AddLegend (no
 * legend yet) is never rendered in the gallery - it only exists so [legendTypeOf] can return an
 * "absent" value the info row filters out - which is why only the text/image bubbles are drawn.
 */
private enum class LegendIconType { AddLegend, TextLegend, ImageLegend }

/** Decoded thumbnail plus the per-photo flags shown on/under it, all read in one Dispatchers.IO pass. */
private data class ThumbnailInfo(
    val bitmap: ImageBitmap?,
    val isFavorite: Boolean,
    val legendType: LegendIconType,
    val hasWarning: Boolean,
)

/** CameraSync3D's stereo-issue badge orange (StereoIssueWarningIcon), same value as Exif3dInfoPanel's WarningColor. */
private val WarningColor = Color(0xFFFF9800)

// CameraSync3D's double-extension convention for SBS/JPS stereo photos, plus the "_raw"/"editedN"/
// "stereo_issuesN" edit marker that sits right before it - kept in sync with AutoAlign.kt's
// sbsDoubleExtensions/existingEditMarker (which write those names); here we only read them back for
// the gallery label. "stereo_issuesN" is SpotStereoIssues.saveSpotIssues's own suffix (see its doc).
private val sbsDoubleExtensions = listOf(".sbs.jpg", ".jps.jpg")
private val editMarkerRegex = Regex("_(raw|edited\\d*|stereo_issues\\d*)$")

/**
 * The "raw"/"edited"/"editedN"/"stereo_issuesN" label shown under a thumbnail, derived from the
 * filename the same way CameraSync3D's gallery derives Photo3d.commentOrRawEdited. Returns "" for
 * arbitrarily-named JPEGs Windows can open that don't carry the marker, so the info row simply
 * omits the label. Not file-private: also used by ImageScreen.kt for the same label shown over the
 * fullscreen photo. This is the raw marker text (grouping/ranking/isRawPhoto checks match against
 * it); use [rawEditedDisplayLabel] for what's actually shown to the user.
 */
internal fun rawEditedLabel(file: File): String {
    val name = file.name
    val matchedExt = sbsDoubleExtensions.firstOrNull { name.endsWith(it, ignoreCase = true) }
    val base = if (matchedExt != null) name.dropLast(matchedExt.length) else file.nameWithoutExtension
    return editMarkerRegex.find(base)?.groupValues?.get(1).orEmpty()
}

/**
 * User-facing text for [rawEditedLabel]'s marker: "raw"/"edited"/"editedN" are shown verbatim,
 * while the "stereo_issues"/"stereo_issuesN" marker (not a word CameraSync3D users would recognize)
 * is shown as "stereo issues spotting" instead.
 */
internal fun rawEditedDisplayLabel(file: File): String {
    val label = rawEditedLabel(file)
    return if (label.startsWith("stereo_issues")) "stereo issues spotting" else label
}

/**
 * The subset of [files] that are each the best raw/edited version within their group (same
 * directory + base name once the marker is stripped, e.g. "photo_raw.sbs.jpg" and
 * "photo_edited2.sbs.jpg" are the same group). Ranked raw < edited < edited2 < ... < editedN.
 * Files with no marker (rawEditedLabel returns "") form a singleton group of their own, so they're
 * always kept. Used by AppViewModel's "keep best of each" filter, driven from ImageScreen's
 * settings menu.
 */
internal fun bestVersionsOnly(files: List<File>): Set<File> =
    files.groupBy(::rawEditedGroupKey).values.map { versions -> versions.maxBy(::rawEditedRank) }.toSet()

private fun rawEditedGroupKey(file: File): String {
    val name = file.name
    val matchedExt = sbsDoubleExtensions.firstOrNull { name.endsWith(it, ignoreCase = true) }
    val base = if (matchedExt != null) name.dropLast(matchedExt.length) else file.nameWithoutExtension
    val strippedBase = editMarkerRegex.replace(base, "")
    // Mirrors AutoAlign.nextAvailableFile's own baseName normalization: CameraSync3D's "_raw"
    // marker sits after a base that already ends in "_" (so stripping it leaves a trailing "_"),
    // while "_editedN" doesn't carry its own leading underscore beyond the one the marker itself
    // consumes - without collapsing both to the same single trailing "_", "photo__raw.sbs.jpg" and
    // "photo_edited.sbs.jpg" strip to "photo_" and "photo" respectively and never group together.
    val normalizedBase = if (strippedBase.endsWith("_")) strippedBase else "${strippedBase}_"
    return File(file.parentFile, normalizedBase).path
}

private fun rawEditedRank(file: File): Int {
    val marker = rawEditedLabel(file)
    return when {
        marker.isEmpty() || marker == "raw" -> 0
        else -> marker.removePrefix("edited").toIntOrNull() ?: 1
    }
}

/**
 * Legend state of a photo, mirroring CameraSync3D's Exif3d.getLegendIconType precedence: a text
 * legend (EXIF UserComment set) wins over an image legend ([hasImageLegend], the Desc3d.hasLegend
 * flag already read from ImageDescription by the caller); neither present means no legend yet.
 */
private fun legendTypeOf(file: File, hasImageLegend: Boolean): LegendIconType {
    val comment = runCatching { Exif.getExifUserComment(file) }.getOrNull()
    return when {
        !comment.isNullOrBlank() -> LegendIconType.TextLegend
        hasImageLegend -> LegendIconType.ImageLegend
        else -> LegendIconType.AddLegend
    }
}

/**
 * Bounded LRU cache of decoded [ThumbnailInfo], shared by every [GalleryThumbnail] and by
 * GalleryScreen's scroll-ahead prefetch ([warm]), so a thumbnail decoded once stays available when
 * it scrolls out of view and back in, instead of re-reading the JPEG from disk each time. Keyed by
 * path + lastModified so an edited photo saved back to the same path doesn't return a stale bitmap.
 * Capped at [maxEntries] to bound memory on large galleries; least-recently-used entries evicted first.
 */
private object ThumbnailCache {
    private const val maxEntries = 400

    // Own SupervisorJob scope (rather than tying decodes to whichever composable/effect triggered
    // them) so a warm() prefetch keeps running even if the row that requested it scrolls back out
    // of view and its LaunchedEffect is cancelled.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashReporting.coroutineHandler)

    private data class Key(val path: String, val lastModified: Long)
    private fun keyOf(file: File) = Key(file.path, file.lastModified())

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<Key, ThumbnailInfo>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, ThumbnailInfo>) = size > maxEntries
        },
    )
    private val inFlight = ConcurrentHashMap<Key, Deferred<ThumbnailInfo>>()

    /** Cached value if already decoded, without waiting - lets GalleryThumbnail skip the loading spinner. */
    fun peek(file: File): ThumbnailInfo? = cache[keyOf(file)]

    /** Decodes (or joins an in-flight decode of, or returns the cached value for) [file]'s thumbnail. */
    suspend fun load(file: File): ThumbnailInfo {
        val key = keyOf(file)
        cache[key]?.let { return it }
        val deferred = inFlight.getOrPut(key) { scope.async { decode(file) } }
        return deferred.await().also {
            cache[key] = it
            inFlight.remove(key, deferred)
        }
    }

    /** Fire-and-forget decode of [file]'s thumbnail into the cache, for scroll-ahead prefetch. */
    fun warm(file: File) {
        if (peek(file) != null || inFlight.containsKey(keyOf(file))) return
        scope.launch { load(file) }
    }

    private fun decode(file: File): ThumbnailInfo {
        // .mpo frames are composed purely in memory (see Mpo.kt's doc comment: browsing/thumbnails
        // never write a converted copy to disk - only an actual edit "Save" does), so its
        // thumbnail still shows both eye-halves like every other photo's, not just the raw left frame.
        val bitmap = runCatching {
            val decoded = if (Mpo.isMpoFile(file)) Mpo.decodeComposedImage(file) else ImageIO.read(file)
            decoded?.toThumbnail(thumbnailPixelWidth, thumbnailPixelHeight)?.toComposeImageBitmap()
        }.getOrNull()
        val desc = runCatching { Exif3d.get3dCameraCharacteristics(file) }.getOrNull()
        return ThumbnailInfo(
            bitmap = bitmap,
            isFavorite = desc?.favorite == true,
            legendType = legendTypeOf(file, hasImageLegend = desc?.hasLegend == true),
            hasWarning = desc?.warning == true,
        )
    }
}

/**
 * A decoded thumbnail with its raw/edited label, favorite/legend info row, and stereo-warning
 * badge - or, for a video file, [ComposableVideoThumbnail]'s extracted frame with a play-circle
 * overlay instead, since the raw/edited/favorite/legend/warning metadata is all EXIF-derived and
 * only meaningful for photos.
 */
@Composable
private fun GalleryThumbnail(file: File, pickerMode: Boolean, selected: Boolean, onClick: () -> Unit) {
    val isVideo = remember(file) { isVideoFilename(file.name) }
    val label = remember(file) { if (isVideo) "" else rawEditedDisplayLabel(file) }
    val info: ThumbnailInfo? = if (isVideo) {
        null
    } else {
        val state by produceState(initialValue = ThumbnailCache.peek(file), key1 = file) {
            value = ThumbnailCache.load(file)
        }
        state
    }
    Column(modifier = Modifier.width(thumbnailWidth)) {
        Box(
            modifier = Modifier
                .size(width = thumbnailWidth, height = thumbnailHeight)
                .clickable(onClick = onClick)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (isVideo) {
                ComposableVideoThumbnail(file = file, modifier = Modifier.fillMaxSize())
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                val loadedBitmap = info?.bitmap
                if (loadedBitmap != null) {
                    Image(
                        bitmap = loadedBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                // Overlaid in the bottom-end corner over the thumbnail, like CameraSync3D's
                // StereoIssueWarningBadge (shown only when the warning is set - hideIfNotActivated).
                if (info?.hasWarning == true) {
                    StereoIssueWarningBadge(modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                }
            }
            if (pickerMode) {
                SelectionBadge(selected = selected, modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
            }
        }
        GalleryThumbnailInfoRow(label = label, info = info)
    }
}

/** Square checkbox shown on each thumbnail in picker mode, with a plain white background so it reads against any thumbnail. */
@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(
        modifier = modifier
            .size(size)
            .background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxSize(0.8f),
            )
        }
    }
}

/** The raw/editedN label + favorite + legend icons shown under a thumbnail (see CameraSync3D's ComposablePictureList). */
@Composable
private fun GalleryThumbnailInfoRow(label: String, info: ThumbnailInfo?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (info?.isFavorite == true) {
            Spacer(Modifier.width(4.dp))
            FavoriteIcon(size = 15.dp)
        }
        val legendType = info?.legendType ?: LegendIconType.AddLegend
        if (legendType != LegendIconType.AddLegend) {
            Spacer(Modifier.width(4.dp))
            LegendIcon(size = 15.dp, type = legendType)
        }
    }
}

/**
 * The orange stereo-issue warning icon on a translucent black circle, mirroring CameraSync3D's
 * StereoIssueWarningIcon (as used by StereoIssueWarningBadge in its gallery). Display-only here.
 */
@Composable
private fun StereoIssueWarningBadge(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(
        modifier = modifier.size(size).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = stringResource(Res.string.gallery_warning_content_description),
            tint = WarningColor,
            modifier = Modifier.fillMaxSize(0.9f).padding(bottom = size * 0.15f),
        )
    }
}

/** Display-only heart, red like CameraSync3D's FavoriteIcon (the gallery never toggles it). */
@Composable
private fun FavoriteIcon(size: Dp) {
    Icon(
        imageVector = Icons.Filled.Favorite,
        contentDescription = stringResource(Res.string.gallery_favorite_content_description),
        tint = Color.Red,
        modifier = Modifier.size(size),
    )
}

/** The text/image legend bubble, mirroring CameraSync3D's LegendIcon (AddLegend is never drawn here). */
@Composable
private fun LegendIcon(size: Dp, type: LegendIconType) {
    if (type == LegendIconType.AddLegend) return
    val isText = type == LegendIconType.TextLegend
    Icon(
        painter = painterResource(if (isText) Res.drawable.ic_text_comment else Res.drawable.ic_image_comment),
        contentDescription = stringResource(
            if (isText) Res.string.gallery_legend_text_content_description
            else Res.string.gallery_legend_image_content_description,
        ),
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(size),
    )
}

internal fun BufferedImage.toThumbnail(maxWidth: Int, maxHeight: Int): BufferedImage {
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height, 1.0)
    val w = (width * scale).toInt().coerceAtLeast(1)
    val h = (height * scale).toInt().coerceAtLeast(1)
    val scaledInstance = getScaledInstance(w, h, AwtImage.SCALE_SMOOTH)
    val output = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = output.createGraphics()
    g.drawImage(scaledInstance, 0, 0, null)
    g.dispose()
    return output
}
