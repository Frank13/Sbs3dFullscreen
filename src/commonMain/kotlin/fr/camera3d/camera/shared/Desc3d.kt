package fr.camera3d.camera.shared

// SHARED FILE: kept identical between CameraSync3D (Android) and sbs3Dfullscreen (Desktop).
// Must stay free of android.* / desktop-only imports. Sync via tools/sync-from-android.ps1.

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Holds the specific use of EXIF for 3d attributes : base, trigger mode, extension mode
 * Serialized as JSON into the standard "ImageDescription" EXIF tag (see Exif3d)
 */

private val mapper = jacksonObjectMapper()

@JsonIgnoreProperties(ignoreUnknown = true)
data class Desc3d(val baseMm : Int=-1, val triggerMode: String="", val extMode : String="", val deviceCount : Int=2, val favorite: Boolean = false, val warning: Boolean = false, val warningComment: String = "", val hasLegend: Boolean = false, val isPhantogram: Boolean = false, val phantogramTiltDeg: Int = -1){

    override fun toString(): String {
        val favStr = if (favorite) """, "favorite": true""" else ""
        val warnStr = if (warning) """, "warning": true""" else ""
        val warnCommentStr = if (warningComment.isNotEmpty()) """, "warningComment": "${warningComment.replace("\"", "\\\"").replace("\n", "\\n")}"""" else ""
        val legendStr = if (hasLegend) """, "hasLegend": true""" else ""
        val phantogramStr = if (isPhantogram) """, "isPhantogram": true""" else ""
        // downward camera tilt from horizontal measured at capture time (phantogram mode), -1 when unknown
        val tiltStr = if (phantogramTiltDeg != -1) """, "phantogramTiltDeg": $phantogramTiltDeg""" else ""
        // triggerMode alone cannot signal "no 3D data": deviceCount==1 photos never have a
        // triggerMode (see below) yet still carry real baseMm/extMode/deviceCount.
        val has3dData = baseMm != -1 || triggerMode != "" || extMode != "" || deviceCount != 2
        if (!has3dData && !favorite && !warning && warningComment.isEmpty() && !hasLegend && !isPhantogram && phantogramTiltDeg == -1) return ""
        if (!has3dData) {
            val parts = mutableListOf<String>()
            if (favorite) parts.add(""""favorite": true""")
            if (warning) parts.add(""""warning": true""")
            if (warningComment.isNotEmpty()) parts.add(""""warningComment": "${warningComment.replace("\"", "\\\"").replace("\n", "\\n")}"""")
            if (hasLegend) parts.add(""""hasLegend": true""")
            if (isPhantogram) parts.add(""""isPhantogram": true""")
            if (phantogramTiltDeg != -1) parts.add(""""phantogramTiltDeg": $phantogramTiltDeg""")
            return """{${parts.joinToString(", ")}}"""
        }
        if (deviceCount==1) return """{"extMode":"$extMode", "deviceCount": "$deviceCount"$favStr$warnStr$warnCommentStr$legendStr$phantogramStr$tiltStr}"""
        else if (baseMm == -1) return """{"triggerMode":"$triggerMode", "extMode":"$extMode", "deviceCount": "$deviceCount"$favStr$warnStr$warnCommentStr$legendStr$phantogramStr$tiltStr}"""
        return """{"baseMm":$baseMm, "triggerMode":"$triggerMode", "extMode":"$extMode", "deviceCount": "$deviceCount"$favStr$warnStr$warnCommentStr$legendStr$phantogramStr$tiltStr}"""
    }
    companion object {
        fun parse(json : String): Desc3d {
            if (json=="") return Desc3d()
            // else parse the json string
            try {
                val desc : Desc3d = mapper.readValue(json, Desc3d::class.java)
                return desc
            }
            catch (e: Exception) {
                System.err.println("Desc3d: error in json parsing : '$json' : ${e.message}")
                return Desc3d()
            }
        }
    }
}
