package fr.camera3d.camera.feature_playlists.service.yamlImporter

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

import fr.camera3d.camera.feature_playlists.domain.CreditsPlacement
import fr.camera3d.camera.feature_playlists.domain.FullscreenScroll
import fr.camera3d.camera.feature_playlists.domain.Playlist
import fr.camera3d.camera.feature_playlists.domain.TextFontFamily
import fr.camera3d.camera.feature_playlists.domain.TextStyleConfig
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * DEFINITION OF THE YAML FORMAT TO STORE PLAYLISTS
 * serialize and deserialize functions from Playlist to YAML and vice versa
 *
 * to change the YAML index file format
 * 1) change YamlPlaylistPhotoV1 or YamlPlaylistV1
 * 2) change PlaylistItem or PlaylistState
 * 3) change createFromYamlPlaylist() in PlaylistViewModel.kt
 */

/**
 * YAML definition for playlist items
 */
data class YamlPlaylistItemV1(val filename : String,
                              val comment : String = "",
                              val commentAnchor : String = "",
                              val commentColor : String = "",
                              val commentZ : Float = 0f,
                              val transition : String = "",
                              val duration : Int = -1,
                              val isHalfWidth : Boolean = false,
                              val isVideo : Boolean = false,
                              val isMuted : Boolean = false,
                              val fullscreenScroll : String = "") // FullscreenScroll name, "" means NONE

/** Converts the YAML fullscreenScroll value to its enum, NONE for a missing or unknown value */
fun String.toFullscreenScroll() : FullscreenScroll =
    runCatching { FullscreenScroll.valueOf(this) }.getOrDefault(FullscreenScroll.NONE)

/** Converts the YAML creditsPlacement value to its enum, END_SLIDE for a missing or unknown value */
fun String.toCreditsPlacement() : CreditsPlacement =
    runCatching { CreditsPlacement.valueOf(this) }.getOrDefault(CreditsPlacement.END_SLIDE)

/**
 * Returns [s] as a YAML double-quoted scalar, escaping backslashes, quotes and line breaks
 * so that a multi-line text (e.g. the credits) fits on a single YAML line
 */
fun yamlDoubleQuoted(s: String) : String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "\\n") + "\""

/**
 * YAML definition for a title/subtitle/credits text style. All fields are nullable: a missing field
 * means "inherit the built-in default" (TextStyleConfig.TITLE_DEFAULT / SUBTITLE_DEFAULT / CREDITS_DEFAULT).
 */
data class YamlTextStyleV1(val fontFamily : String? = null,
                           val fontSizePercent : Float? = null, // % of slide height
                           val fontSizeSp : Float? = null, // legacy, read-only: converted to a % on import
                           val bold : Boolean? = null,
                           val italic : Boolean? = null,
                           val underline : Boolean? = null,
                           val colorHex : String? = null,
                           val backgroundColorHex : String? = null,
                           val backgroundOpacityPercent : Float? = null,
                           val topPercent : Float? = null,
                           val leftPercent : Float? = null,
                           val rightPercent : Float? = null,
                           val bottomPercent : Float? = null)

fun YamlTextStyleV1?.toTextStyleConfig(default: TextStyleConfig) : TextStyleConfig {
    if (this == null) return default
    return TextStyleConfig(
        fontFamily = fontFamily?.let { runCatching { TextFontFamily.valueOf(it) }.getOrNull() } ?: default.fontFamily,
        // legacy sp -> % assuming a ~360dp slide height (24sp ~ 6.7%)
        fontSizePercent = fontSizePercent ?: fontSizeSp?.let { it / 3.6f } ?: default.fontSizePercent,
        bold = bold ?: default.bold,
        italic = italic ?: default.italic,
        underline = underline ?: default.underline,
        colorHex = colorHex ?: default.colorHex,
        backgroundColorHex = backgroundColorHex ?: default.backgroundColorHex,
        backgroundOpacityPercent = backgroundOpacityPercent ?: default.backgroundOpacityPercent,
        topPercent = topPercent ?: default.topPercent,
        leftPercent = leftPercent ?: default.leftPercent,
        rightPercent = rightPercent ?: default.rightPercent,
        bottomPercent = bottomPercent ?: default.bottomPercent,
    )
}

/**
 * Returns a nested-YAML block (e.g. "titleStyle :\n  fontSizePercent : 8\n") with only the fields
 * that differ from [default], or "" if the style is identical to the default.
 */
fun TextStyleConfig.toYamlBlock(blockName: String, default: TextStyleConfig) : String {
    val fields = (if (fontFamily != default.fontFamily) {"  fontFamily : \"${fontFamily.name}\"\n"} else {""}) +
            (if (fontSizePercent != default.fontSizePercent) {"  fontSizePercent : $fontSizePercent\n"} else {""}) +
            (if (bold != default.bold) {"  bold : $bold\n"} else {""}) +
            (if (italic != default.italic) {"  italic : $italic\n"} else {""}) +
            (if (underline != default.underline) {"  underline : $underline\n"} else {""}) +
            (if (colorHex != default.colorHex) {"  colorHex : \"$colorHex\"\n"} else {""}) +
            (if (backgroundColorHex != default.backgroundColorHex) {"  backgroundColorHex : \"$backgroundColorHex\"\n"} else {""}) +
            (if (backgroundOpacityPercent != default.backgroundOpacityPercent) {"  backgroundOpacityPercent : $backgroundOpacityPercent\n"} else {""}) +
            (if (topPercent != default.topPercent) {"  topPercent : $topPercent\n"} else {""}) +
            (if (leftPercent != default.leftPercent) {"  leftPercent : $leftPercent\n"} else {""}) +
            (if (rightPercent != default.rightPercent) {"  rightPercent : $rightPercent\n"} else {""}) +
            (if (bottomPercent != default.bottomPercent) {"  bottomPercent : $bottomPercent\n"} else {""})
    return if (fields.isEmpty()) {""} else {"$blockName :\n$fields"}
}

/**
 * YAML definition for playlists
 */
data class YamlPlaylistV1(val formatVersion : Float,
                          val name : String ,
                          val defaultDuration : Long = 2000,
                          val defaultTransition : String = "",
                          val soundFilename : String = "",
                          val subtitle : String = "",
                          val titleZ : Float = 0f,
                          val subtitleZ : Float = 0f,
                          val titleStyle : YamlTextStyleV1? = null,
                          val subtitleStyle : YamlTextStyleV1? = null,
                          val isAutomated : Boolean = true,
                          val scrollSpeedLimitPercent : Float = 5f, // % of the screen per second
                          val soundtrack : List<String> = listOf(), // mp3 filenames played one after the other
                          val credits : String = "", // multi-line, written with \n escapes
                          val creditsZ : Float = 0f,
                          val creditsStyle : YamlTextStyleV1? = null,
                          val creditsPlacement : String = "", // CreditsPlacement name, "" means END_SLIDE
                          val photos : List<YamlPlaylistItemV1> = listOf() // list of the playlist photos
)

fun getYamlPlaylistFromYaml(yamlDocument : String): YamlPlaylistV1{
    val mapper = YAMLMapper.builder().addModule(kotlinModule()).build() // Enable YAML parsing with Kotlin support
    val playlist = mapper.readValue(yamlDocument, YamlPlaylistV1::class.java)
    if (playlist.formatVersion != 1.0f){
        throw Exception("expected version 1.0")
    }
    else{
        return playlist
    }
}

/**
 * Returns a YAML string representing the Playlist
 */
fun PlaylistToYAML(p: Playlist) : String{
    val playlistCore = "# Playlist index for CameraSync3D\n" +
            "formatVersion : 1.0\n" +
            "name : \"${p.name}\"\n" +
            "defaultDuration : ${p.defaultDurationS}\n" +
            if (p.defaultTransition != "") {"defaultTransition : \"${p.defaultTransition}\"\n"} else {""} +
            if (p.soundFilename != "") {"soundFilename : ${p.soundFilename}\n"} else {""} +
            if (p.subtitle != "") {"subtitle : \"${p.subtitle}\"\n"} else {""} +
            if (p.titleZPercent != 0f) {"titleZ : ${p.titleZPercent}\n"} else {""} +
            if (p.subtitleZPercent != 0f) {"subtitleZ : ${p.subtitleZPercent}\n"} else {""} +
            p.titleStyle.toYamlBlock("titleStyle", TextStyleConfig.TITLE_DEFAULT) +
            p.subtitleStyle.toYamlBlock("subtitleStyle", TextStyleConfig.SUBTITLE_DEFAULT) +
            if (!p.isAutomated) {"isAutomated : false\n"} else {""} +
            if (p.scrollSpeedLimitPercent != 5f) {"scrollSpeedLimitPercent : ${p.scrollSpeedLimitPercent}\n"} else {""} +
            if (p.soundtrack.isNotEmpty()) {"soundtrack :\n" + p.soundtrack.joinToString("") { "- \"$it\"\n" }} else {""} +
            if (p.credits != "") {"credits : ${yamlDoubleQuoted(p.credits)}\n"} else {""} +
            if (p.creditsZPercent != 0f) {"creditsZ : ${p.creditsZPercent}\n"} else {""} +
            p.creditsStyle.toYamlBlock("creditsStyle", TextStyleConfig.CREDITS_DEFAULT) +
            if (p.creditsPlacement != CreditsPlacement.END_SLIDE) {"creditsPlacement : \"${p.creditsPlacement.name}\"\n"} else {""} +
            if (p.photos.isNotEmpty()) {"photos :\n"} else {""}
    val items = p.photos.map {"- filename : \"${it.filename}\"\n" + // WARNING 1+ space after ":" is important
            "  comment : \"${it.comment}\"\n" +
            if (it.commentColor != "") {"  commentAnchor : \"${it.commentAnchor}\"\n"} else {""} +
            if (it.commentColor != "") {"  commentColor : \"${it.commentColor}\"\n"} else {""} +
            if (it.commentZPercent != 0f) {"  commentZ : ${it.commentZPercent}\n"} else {""} +
            if (it.durationS != -1) {"  duration : ${it.durationS}\n"} else {""} + // WARNING { } are important. Otherwise do not go to next line
            if (it.transition != "") {"  transition : \"${it.transition}\"\n"} else {""} +
            if (it.isHalfWidth) {"  isHalfWidth : true\n"} else {""} +
            if (it.isVideo) {"  isVideo : true\n"} else {""} +
            if (it.isMuted) {"  isMuted : true\n"} else {""} +
            if (it.fullscreenScroll != FullscreenScroll.NONE) {"  fullscreenScroll : \"${it.fullscreenScroll.name}\"\n"} else {""}
    }
    val itemsStr = items.joinToString("")
    return "$playlistCore$itemsStr"
}
