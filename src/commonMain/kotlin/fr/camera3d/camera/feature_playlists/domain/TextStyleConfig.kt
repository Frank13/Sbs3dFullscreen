package fr.camera3d.camera.feature_playlists.domain

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

enum class TextFontFamily { DEFAULT, SERIF, SANS_SERIF, MONOSPACE, CURSIVE }

/**
 * Style + on-screen bounding-box position for a piece of slide text (title, subtitle or credits).
 * topPercent/leftPercent/rightPercent behave like CSS `position: absolute; top; left; right`:
 * the box's width is `100% - leftPercent - rightPercent` and text is centered within it.
 * bottomPercent is only used by the credits, whose box height is `100% - topPercent - bottomPercent`.
 */
data class TextStyleConfig(
    val fontFamily: TextFontFamily = TextFontFamily.DEFAULT,
    val fontSizePercent: Float = 15f, // % of slide height, so previews match the beamer projection
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val colorHex: String = "#FFFFFF",
    val backgroundColorHex: String = "#000000",
    val backgroundOpacityPercent: Float = 0f, // 0 = no background box, 100 = opaque
    val topPercent: Float = 40f,
    val leftPercent: Float = 10f,
    val rightPercent: Float = 10f,
    val bottomPercent: Float = 0f, // credits only
) {
    companion object {
        val TITLE_DEFAULT = TextStyleConfig()
        val SUBTITLE_DEFAULT = TextStyleConfig(fontSizePercent = 10f, topPercent = 55f)
        val CREDITS_DEFAULT = TextStyleConfig(fontSizePercent = 6f, topPercent = 0f, leftPercent = 0f, rightPercent = 0f, bottomPercent = 0f)
    }
}
