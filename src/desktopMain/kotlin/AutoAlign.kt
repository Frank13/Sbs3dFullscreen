import androidx.compose.ui.graphics.ImageBitmap
import fr.camera3d.camera.feature_edit.autoalign.computeValidCropRect
import fr.camera3d.camera.feature_edit.autoalign.detectFeaturesFromColorMats
import fr.camera3d.camera.feature_edit.autoalign.estimateMatrix
import fr.camera3d.camera.feature_edit.autoalign.extractParametersFromMatrix
import fr.camera3d.camera.shared.nextAvailableSuffixedFilename
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.features.DescriptorMatcher
import org.opencv.geometry.Geometry
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Which of CameraSync3D's two stereo auto-align algorithms to run - see AutoAlign.kt's header. */
enum class AlignKind { AFFINE, HOMOGRAPHY }

/**
 * The result of any align/crop/spot-issues/keep-half save (see [AutoAlign.writeAlignedResult]):
 * [file] always holds valid saved pixels, but [exifError] can be non-null even then - [Exif.copyExif]
 * catches and logs its own failures rather than throwing, since by the time it runs [file]'s pixel
 * bytes are already on disk (see writeAlignedResult) and a metadata-copy hiccup shouldn't discard a
 * successful pixel edit. Callers surface [exifError] via AppViewModel.SaveToast so a save that lost
 * the original's EXIF (in particular the Exif3d "3D characteristics" packed into ImageDescription)
 * isn't silently reported as a plain success.
 */
data class SavedFile(val file: File, val exifError: String? = null)

/**
 * Desktop wrapper around the Mat-in/Mat-out core synced from CameraSync3D
 * (feature_edit/autoalign/AutoAlignCore.kt): loads OpenCV's native library, does File <-> Mat
 * I/O (Android's org.opencv.android.Utils.bitmapToMat/matToBitmap equivalent), and re-derives the
 * warp+crop math from Android's autoAlign2d/autoAlignHomography in Mat/Rect terms (those functions
 * themselves weren't synced - only the feature-detection/matrix-estimation core was - since
 * they're saturated with Bitmap calls).
 *
 * [alignAndCrop]/[alignAndCropHomography] are hand-ported copies of CameraSync3D's
 * autoalign2d.kt/autoalignByHomography.kt, not verbatim syncs (they can't be - see above). To
 * catch drift, tools/sync-from-android.ps1 hashes the live Android files and compares them
 * against the SOURCE_HASH values below; it warns if they no longer match, meaning the Android
 * side changed and this file needs to be hand-updated (and the hash refreshed) to match.
 * SOURCE_HASH autoalign2d.kt: 43244260E5A367907825FB8FE188A5905640D8E1202BE50655566AC10A113E08
 * SOURCE_HASH autoalignByHomography.kt: 16665BA98541D426F3C4278005D077F05F859F0523E89AF57DED8EBFD60F8E42
 */
object AutoAlign {
    @Volatile
    private var openCvLoaded = false

    private fun ensureOpenCvLoaded() {
        if (!openCvLoaded) {
            synchronized(this) {
                if (!openCvLoaded) {
                    // Packaged app: the DLL ships in Compose's resources dir. Dev runs have no such
                    // property and fall back to -Djava.library.path (set by build.gradle.kts).
                    val bundled = System.getProperty("compose.application.resources.dir")
                        ?.let { File(it, System.mapLibraryName(Core.NATIVE_LIBRARY_NAME)) }
                        ?.takeIf { it.exists() }
                    if (bundled != null) System.load(bundled.absolutePath)
                    else System.loadLibrary(Core.NATIVE_LIBRARY_NAME)
                    openCvLoaded = true
                }
            }
        }
    }

    /**
     * Decodes a file straight to a 4-channel Mat (BGRA) regardless of source format/channel count.
     * Reads the bytes via Java's File I/O and decodes with [Imgcodecs.imdecode] rather than calling
     * [Imgcodecs.imread] directly - imread takes a narrow (non-Unicode) path on Windows, so it
     * silently fails to open any path containing non-ASCII characters (e.g. accented folder/file
     * names), returning an empty Mat that then blows up downstream in cvtColor.
     */
    internal fun fileToMat(file: File): Mat {
        ensureOpenCvLoaded()
        if (Mpo.isMpoFile(file)) return mpoToMat(file)
        val bytes = file.readBytes()
        val decoded = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
        val withAlpha = Mat()
        Imgproc.cvtColor(decoded, withAlpha, Imgproc.COLOR_BGR2BGRA)
        decoded.release()
        return withAlpha
    }

    /**
     * Composes an .mpo's two stereo frames (see [Mpo.splitFrames]) into one side-by-side Mat,
     * entirely in memory - mirrors [Mpo.decodeComposedImage] but via OpenCV's Mat/hconcat instead
     * of AWT's Graphics2D, since this is the read side of the align/crop/manual-align/spot-issues
     * pipeline, which works in Mat throughout. Never touches disk itself (see Mpo.kt's doc
     * comment) - a real .sbs.jpg only appears once [writeAlignedResult] actually writes an edited
     * result under [nextAvailableFile]'s ".sbs.jpg" naming for an MPO source.
     */
    private fun mpoToMat(file: File): Mat {
        val frames = Mpo.splitFrames(file.readBytes())
        require(frames.size >= 2) { "MPO file has fewer than 2 frames: ${file.name}" }
        val left = Imgcodecs.imdecode(MatOfByte(*frames[0]), Imgcodecs.IMREAD_COLOR)
        val right = Imgcodecs.imdecode(MatOfByte(*frames[1]), Imgcodecs.IMREAD_COLOR)
        val combined = Mat()
        Core.hconcat(listOf(left, right), combined)
        left.release()
        right.release()
        val withAlpha = Mat()
        Imgproc.cvtColor(combined, withAlpha, Imgproc.COLOR_BGR2BGRA)
        combined.release()
        return withAlpha
    }

    internal fun matToImageBitmap(mat: Mat): ImageBitmap {
        val buf = MatOfByte()
        Imgcodecs.imencode(".png", mat, buf)
        val bytes = buf.toArray()
        buf.release()
        return bytes.decodeToImageBitmap()
    }

    /**
     * Classic full-color anaglyph: [leftBgr]'s red channel combined with [rightBgr]'s green and
     * blue channels (both must already be 3-channel BGR, OpenCV's channel order throughout this
     * codebase). Shared by [Share.buildAnaglyph] and ClickAlign's Shift-held full-width preview
     * (see CLAUDE.md's Shift-preview note) so both anaglyph renderers agree.
     */
    internal fun mergeAnaglyphBgr(leftBgr: Mat, rightBgr: Mat): Mat {
        val leftChannels = ArrayList<Mat>(3)
        Core.split(leftBgr, leftChannels)
        val rightChannels = ArrayList<Mat>(3)
        Core.split(rightBgr, rightChannels)
        val anaglyph = Mat()
        Core.merge(listOf(rightChannels[0], rightChannels[1], leftChannels[2]), anaglyph)
        (leftChannels + rightChannels).forEach { it.release() }
        return anaglyph
    }

    /**
     * [AlignKind.AFFINE]'s detected zoom ratio (1.0 = no zoom difference between the two eyes) and
     * rotation in degrees between the two eyes, alongside the recombined preview - surfaced so the
     * "Correct Zoom" toast can report what was actually detected/corrected. Null for
     * [AlignKind.HOMOGRAPHY], which doesn't decompose its warp into a single scale+rotation pair.
     */
    data class AutoAlignResult(val bitmap: ImageBitmap, val zoomScale: Float?, val rotationDegrees: Float?)

    /**
     * Splits [file]'s side-by-side stereo photo into left/right, auto-aligns them per [kind], and
     * returns the recombined side-by-side preview - or null if not enough features were found.
     * [useNewOpenCv5] switches from ORB+RANSAC to OpenCV 5's SIFT+USAC_MAGSAC, mirroring
     * CameraSync3D's same-named toggle (more distinctive on low-texture/repetitive scenes, at the
     * cost of extra CPU time).
     */
    fun autoAlign(file: File, kind: AlignKind, useNewOpenCv5: Boolean = false): AutoAlignResult? {
        val outcome = computeAligned(file, kind, useNewOpenCv5) ?: return null
        val bitmap = matToImageBitmap(outcome.mat)
        outcome.mat.release()
        return AutoAlignResult(bitmap, outcome.zoomScale, outcome.rotationDegrees)
    }

    /**
     * Redoes the [kind] alignment against the original [file] on disk (not a cached preview, to
     * avoid re-compressing an already-lossy preview - mirrors CameraSync3D's save flow, which
     * reapplies the pending align to the original at save time) and writes the result under a new
     * filename next to it, copying [file]'s entire EXIF metadata across. Returns null if alignment
     * itself failed; see [SavedFile] for what a non-null result guarantees.
     */
    fun saveAligned(file: File, kind: AlignKind, useNewOpenCv5: Boolean = false): SavedFile? {
        val outcome = computeAligned(file, kind, useNewOpenCv5) ?: return null
        val saved = writeAlignedResult(file, outcome.mat)
        outcome.mat.release()
        return saved
    }

    /**
     * Converts [combinedBgra] to BGR, writes it under [file]'s next available "_<suffix>N" name (see
     * [nextAvailableFile]), and copies [file]'s entire EXIF metadata across (including the Exif3d
     * data packed into ImageDescription - see Exif.copyExif). Shared by [saveAligned], ManualAlign's
     * saveManualAlign, Crop's saveCrop and SpotStereoIssues' saveSpotIssues so every save path
     * writes results the same way; [suffix] defaults to "edited" (CameraSync3D's own suffix word)
     * but SpotStereoIssues passes "stereo_issues" instead, so those files are distinguishable from a
     * plain align/crop/manual-nudge edit at a glance. Encodes with [Imgcodecs.imencode] and writes
     * the bytes via Java's File I/O rather than calling [Imgcodecs.imwrite] directly - like
     * [fileToMat]'s imread, imwrite takes a narrow (non-Unicode) path on Windows and silently fails
     * to write anywhere under a non-ASCII folder/file name.
     */
    internal fun writeAlignedResult(file: File, combinedBgra: Mat, suffix: String = "edited"): SavedFile {
        val bgr = Mat()
        Imgproc.cvtColor(combinedBgra, bgr, Imgproc.COLOR_BGRA2BGR)
        val destFile = nextAvailableFile(file, suffix)
        val buf = MatOfByte()
        Imgcodecs.imencode("." + destFile.extension, bgr, buf)
        destFile.writeBytes(buf.toArray())
        buf.release()
        bgr.release()
        val exifError = Exif.copyExif(file, destFile)
        return SavedFile(destFile, exifError)
    }

    /** CameraSync3D's own double-extension convention for SBS/JPS stereo photos (see ImageProcessing.kt). */
    private val sbsDoubleExtensions = listOf(".sbs.jpg", ".jps.jpg")

    /**
     * Matches an existing edit marker right before the extension - CameraSync3D's own "_raw"/
     * "editedN" (see ImageProcessing.kt's getEditedFilenameAndShift; this app uses the same
     * "edited" suffix word, not one of its own) plus this app's own "_stereo_issuesN" (see
     * [writeAlignedResult]'s suffix param) - so it can be replaced rather than stacked
     * ("photo_edited.jpg" re-aligned becomes "photo_edited2.jpg", not "photo_edited_edited.jpg",
     * and likewise re-editing a "photo_stereo_issues.jpg" with any tool replaces that marker too).
     * Always requires a leading underscore, so an unrelated name that merely ends in those letters
     * (e.g. "unedited.jpg") is left alone.
     */
    private val existingEditMarker = Regex("_(raw|edited\\d*|stereo_issues\\d*)$")

    /**
     * "name_<suffix>.ext", incrementing to "name_<suffix>2.ext", "name_<suffix>3.ext", ... until a
     * name that doesn't already exist on disk is found - [suffix] defaults to "edited"
     * (CameraSync3D's own suffix word, see ImageProcessing.kt's getEditedFilenameAndShift), not one
     * of this app's own; SpotStereoIssues passes "stereo_issues" instead (this app's own word, no
     * Android counterpart). Delegates to the shared nextAvailableSuffixedFilename (synced verbatim
     * from CameraSync3D's fr.camera3d.camera.shared.FilenameIncrement.kt, which
     * getEditedFilenameAndShift also calls) - [source]'s extension is matched against
     * [sbsDoubleExtensions] first (so the suffix lands before ".sbs.jpg"/".jps.jpg" as a whole, not
     * before just ".jpg"), falling back to the plain single extension for arbitrarily-named JPEGs
     * Windows can open. Any existing [existingEditMarker] is stripped from the base name first, so
     * re-editing an already-marked file replaces its marker instead of stacking one on top of
     * another; currentSuffixNumber is always 0 since [source] is always the original file on disk
     * (see [saveAligned]'s doc comment) with whatever marker it already carries removed, not a
     * number to continue from.
     */
    internal fun nextAvailableFile(source: File, suffix: String = "edited"): File {
        val parent = source.absoluteFile.parentFile
        val name = source.name
        val matchedExt = sbsDoubleExtensions.firstOrNull { name.endsWith(it, ignoreCase = true) }
        val ext = when {
            matchedExt != null -> matchedExt
            // An .mpo source's two frames are composed into a plain side-by-side Mat by fileToMat
            // (see mpoToMat) before any edit runs, so the saved result is a normal SBS JPEG - never
            // written back out as .mpo itself.
            Mpo.isMpoFile(source) -> ".sbs.jpg"
            else -> "." + source.extension.ifEmpty { "jpg" }
        }
        val nameWithoutExt = if (matchedExt != null) name.dropLast(matchedExt.length) else source.nameWithoutExtension
        val strippedBaseName = existingEditMarker.replace(nameWithoutExt, "")
        val baseName = if (strippedBaseName.endsWith("_")) strippedBaseName else "${strippedBaseName}_"
        val (filenameNew, _) = nextAvailableSuffixedFilename(
            existingFile = source,
            baseName = baseName,
            suffix = suffix,
            ext = ext,
            currentSuffixNumber = 0,
        )
        return File(parent, filenameNew)
    }

    /** [computeAligned]'s result: the recombined Mat plus [AlignKind.AFFINE]'s detected zoom/rotation (null for HOMOGRAPHY). */
    private data class ComputedAlignment(val mat: Mat, val zoomScale: Float?, val rotationDegrees: Float?)

    /** One [when] branch's aligned left/right pair, plus [AlignKind.AFFINE]'s zoom/rotation (null for HOMOGRAPHY). */
    private data class AlignedPair(val left: Mat, val right: Mat, val zoomScale: Float? = null, val rotationDegrees: Float? = null)

    /** Splits [file] into left/right Mats, aligns them per [kind], and hconcats the result back together. */
    private fun computeAligned(file: File, kind: AlignKind, useNewOpenCv5: Boolean): ComputedAlignment? {
        val fullMat = fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val leftMat = Mat(fullMat, Rect(0, 0, halfW, h)).clone()
        val rightMat = Mat(fullMat, Rect(halfW, 0, w - halfW, h)).clone()
        fullMat.release()

        try {
            val (leftFeatures, rightFeatures) = detectFeaturesFromColorMats(leftMat, rightMat, useNewOpenCv5)
            val (kp1, des1) = leftFeatures
            val (kp2, des2) = rightFeatures
            if (des1.empty() || des2.empty()) {
                kp1.release(); des1.release(); kp2.release(); des2.release()
                return null
            }

            val alignedPair = when (kind) {
                AlignKind.AFFINE -> {
                    val m = estimateMatrix(kp1, des1, kp2, des2, useNewOpenCv5)
                    kp1.release(); des1.release(); kp2.release(); des2.release()
                    if (m == null) return null
                    val (scale, rotation) = extractParametersFromMatrix(m)
                    m.release()
                    val (left, right) = alignAndCrop(leftMat, rightMat, scale, rotation)
                    AlignedPair(left, right, zoomScale = scale, rotationDegrees = rotation)
                }
                AlignKind.HOMOGRAPHY -> {
                    val (left, right) = alignAndCropHomography(leftMat, rightMat, kp1, des1, kp2, des2, useNewOpenCv5)
                        ?: return null
                    AlignedPair(left, right)
                }
            }

            val combined = Mat()
            Core.hconcat(listOf(alignedPair.left, alignedPair.right), combined)
            alignedPair.left.release()
            alignedPair.right.release()
            return ComputedAlignment(combined, alignedPair.zoomScale, alignedPair.rotationDegrees)
        } finally {
            leftMat.release()
            rightMat.release()
        }
    }

    /**
     * Aligns left/right by reducing the bigger one (zoom + rotation) and cropping the other -
     * re-derivation of Android's autoAlign2d (feature_edit/autoalign/autoalign2d.kt) in Mat/Rect
     * terms instead of Bitmap, since that function itself is Bitmap-saturated and wasn't synced.
     */
    private fun alignAndCrop(leftMat: Mat, rightMat: Mat, scale: Float, rotation: Float): Pair<Mat, Mat> {
        val applyToLeft = scale >= 1f
        val reduceFactor = if (applyToLeft) 1.0 / scale.toDouble() else scale.toDouble()
        val angle = if (applyToLeft) -rotation.toDouble() else rotation.toDouble()

        val srcMat = if (applyToLeft) leftMat else rightMat
        val w = srcMat.width().toDouble()
        val h = srcMat.height().toDouble()

        val rotMat = Geometry.getRotationMatrix2D(Point(w / 2, h / 2), angle, reduceFactor)
        val dstMat = Mat(srcMat.size(), srcMat.type())
        Imgproc.warpAffine(srcMat, dstMat, rotMat, srcMat.size())
        rotMat.release()

        // Largest axis-aligned inscribed rectangle in the rotated content - see autoalign2d.kt's
        // autoAlign2d for the derivation of this formula.
        val thetaRad = abs(Math.toRadians(angle))
        val cosT = cos(thetaRad)
        val sinT = sin(thetaRad)
        val cos2T = cos(2 * thetaRad)

        val cropW: Int
        val cropH: Int
        if (cos2T > 0.001 &&
            reduceFactor * (w * cosT - h * sinT) / cos2T > 0 &&
            reduceFactor * (h * cosT - w * sinT) / cos2T > 0
        ) {
            cropW = (reduceFactor * (w * cosT - h * sinT) / cos2T).toInt()
            cropH = (reduceFactor * (h * cosT - w * sinT) / cos2T).toInt()
        } else {
            val r = reduceFactor * minOf(w, h) / sqrt(2.0)
            cropW = r.toInt()
            cropH = r.toInt()
        }

        val cropX = ((w - cropW) / 2).toInt().coerceAtLeast(0)
        val cropY = ((h - cropH) / 2).toInt().coerceAtLeast(0)
        val finalCropW = minOf(cropW, srcMat.width() - cropX).coerceAtLeast(1)
        val finalCropH = minOf(cropH, srcMat.height() - cropY).coerceAtLeast(1)

        val croppedTransformed = Mat(dstMat, Rect(cropX, cropY, finalCropW, finalCropH)).clone()
        dstMat.release()

        val otherMat = if (applyToLeft) rightMat else leftMat
        val otherCropX = ((otherMat.width() - finalCropW) / 2).coerceAtLeast(0)
        val otherCropY = ((otherMat.height() - finalCropH) / 2).coerceAtLeast(0)
        val croppedOther = Mat(otherMat, Rect(otherCropX, otherCropY, finalCropW, finalCropH)).clone()

        return if (applyToLeft) Pair(croppedTransformed, croppedOther) else Pair(croppedOther, croppedTransformed)
    }

    /**
     * Warps the right image into the left's coordinate system via a full homography (perspective
     * transform, correcting perspective distortion rather than just scale+rotation+translation
     * like [alignAndCrop]), then crops both to the region [computeValidCropRect] guarantees is
     * valid - re-derivation of Android's autoAlignHomography
     * (feature_edit/autoalign/autoalignByHomography.kt) in Mat/Rect terms instead of Bitmap, since
     * that function itself is Bitmap-saturated and wasn't synced. Releases kp1/des1/kp2/des2.
     */
    private fun alignAndCropHomography(
        leftMat: Mat,
        rightMat: Mat,
        kp1: org.opencv.core.MatOfKeyPoint,
        des1: Mat,
        kp2: org.opencv.core.MatOfKeyPoint,
        des2: Mat,
        useNewOpenCv5: Boolean,
    ): Pair<Mat, Mat>? {
        val matcher = DescriptorMatcher.create(if (useNewOpenCv5) DescriptorMatcher.FLANNBASED else DescriptorMatcher.BRUTEFORCE_HAMMING)
        val matches = MatOfDMatch()
        matcher.match(des1, des2, matches)
        val goodMatches = matches.toList().sortedBy { it.distance }.let { if (it.size > 200) it.take(200) else it }
        matches.release()

        if (goodMatches.size < 4) {
            kp1.release(); des1.release(); kp2.release(); des2.release()
            return null
        }

        val listKp1 = kp1.toList()
        val listKp2 = kp2.toList()
        val pts1List = ArrayList<Point>()
        val pts2List = ArrayList<Point>()
        for (match in goodMatches) {
            pts1List.add(listKp1[match.queryIdx].pt)
            pts2List.add(listKp2[match.trainIdx].pt)
        }
        kp1.release(); des1.release(); kp2.release(); des2.release()

        val pts1 = MatOfPoint2f().apply { fromList(pts1List) }
        val pts2 = MatOfPoint2f().apply { fromList(pts2List) }
        // H maps right -> left coordinate system.
        val H = Geometry.findHomography(pts2, pts1, if (useNewOpenCv5) Geometry.USAC_MAGSAC else Geometry.RANSAC)
        pts1.release(); pts2.release()
        if (H == null || H.empty()) {
            H?.release()
            return null
        }

        val w = leftMat.width().toDouble()
        val h = leftMat.height().toDouble()
        val size = Size(w, h)
        val warpedRight = Mat(size, rightMat.type())
        Imgproc.warpPerspective(rightMat, warpedRight, H, size)

        val cropRect = computeValidCropRect(H, w, h)
        H.release()

        return if (cropRect != null) {
            val cx = cropRect.x.coerceAtLeast(0)
            val cy = cropRect.y.coerceAtLeast(0)
            val cw = minOf(cropRect.width, w.toInt() - cx).coerceAtLeast(1)
            val ch = minOf(cropRect.height, h.toInt() - cy).coerceAtLeast(1)
            val croppedLeft = Mat(leftMat, Rect(cx, cy, cw, ch)).clone()
            val croppedRight = Mat(warpedRight, Rect(cx, cy, cw, ch)).clone()
            warpedRight.release()
            Pair(croppedLeft, croppedRight)
        } else {
            Pair(leftMat.clone(), warpedRight)
        }
    }
}
