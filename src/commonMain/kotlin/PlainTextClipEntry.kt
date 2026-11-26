package fr.camera3d.camera.common.ui_components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

// NOT synced from CameraSync3D: desktop implementation of the helper that the synced
// PortablePlaylistUiAtoms.kt calls (the Android app has its own ClipData-based version in the same
// package). Lives in commonMain (not desktopMain) because the synced file it serves is in
// commonMain, and outside the fr/camera3d/camera/ directory so it isn't mistaken for a synced file.
// ClipEntry(Transferable) is still @ExperimentalComposeUiApi on desktop, but it's the only way
// to put plain text on the clipboard through the non-deprecated Clipboard API.
@OptIn(ExperimentalComposeUiApi::class)
fun plainTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
