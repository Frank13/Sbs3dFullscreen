import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which eye-half the "Copy from image" tool reads from and which one it paints into. */
enum class CopyDirection {
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT;

    val targetIsRight: Boolean get() = this == LEFT_TO_RIGHT
}

// Longest side of the brush mask, in mask pixels - the mask is stored at a reduced resolution
// (upsampled bilinearly on display and at save time) since the brush is soft-edged anyway, so a
// full-resolution mask would only cost memory and per-stroke redraw time without visibly changing
// the result.
private const val MaskMaxSide = 1024

// Inside this fraction of the brush radius the copy is fully opaque; from there to the edge it
// fades smoothly to 0 - the "brush with a gradient" the user asked for.
private const val BrushHardCoreFraction = 0.5f

/**
 * The painted area of the "Copy from image" tool: one alpha byte per mask pixel over the TARGET
 * eye-half (see [CopyDirection]), 255 = fully replaced by the aligned source pixel, 0 = untouched.
 * Kept separate from the photo itself (rather than painting straight into a bitmap copy) so the
 * live preview only needs to redraw this small mask, not the whole full-resolution photo, on every
 * mouse move - see ImageScreen's CopyFromImageHalf, which composites it on the fly.
 *
 * Each dab takes the max with what's already there rather than accumulating, so going back over an
 * area within one stroke doesn't harden the brush's soft edge.
 */
class CopyFromImageMask(eyeWidth: Int, eyeHeight: Int) {
    private val scale = min(1f, MaskMaxSide.toFloat() / max(eyeWidth, eyeHeight))
    val width = (eyeWidth * scale).roundToInt().coerceAtLeast(1)
    val height = (eyeHeight * scale).roundToInt().coerceAtLeast(1)
    private val alpha = ByteArray(width * height)

    // Bumped on every stamp so Compose redraws the preview - the ByteArray itself isn't observable.
    var version by mutableStateOf(0)
        private set

    val isEmpty: Boolean get() = version == 0

    /**
     * Paints a stroke segment from [from] to [to] (fractions of the eye-half, see PointFraction)
     * with dabs spaced a quarter radius apart, [radiusFraction] being a fraction of the eye-half's
     * height. [from] null = the first dab of a new stroke.
     */
    fun stamp(from: PointFraction?, to: PointFraction, radiusFraction: Float) {
        val r = radiusFraction * height
        if (r <= 0f) return
        val tx = to.x * width
        val ty = to.y * height
        val fx = from?.let { it.x * width } ?: tx
        val fy = from?.let { it.y * height } ?: ty
        val steps = max(1, ceil(hypot(tx - fx, ty - fy) / (r / 4f)).toInt())
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            dab(fx + (tx - fx) * t, fy + (ty - fy) * t, r)
        }
        version++
    }

    private fun dab(cx: Float, cy: Float, r: Float) {
        val x0 = max(0, floor(cx - r).toInt())
        val x1 = min(width - 1, ceil(cx + r).toInt())
        val y0 = max(0, floor(cy - r).toInt())
        val y1 = min(height - 1, ceil(cy + r).toInt())
        val soft = r * (1f - BrushHardCoreFraction)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                if (d >= r) continue
                val a = if (d <= r - soft) 1f else {
                    val u = (r - d) / soft
                    u * u * (3f - 2f * u)
                }
                val value = (a * 255f).roundToInt()
                val index = y * width + x
                if (value > (alpha[index].toInt() and 0xFF)) alpha[index] = value.toByte()
            }
        }
    }

    /** Alpha-only bitmap for the live preview's DstIn compositing - see ImageScreen's CopyFromImageHalf. */
    fun toImageBitmap(): ImageBitmap {
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(width, height, ColorType.ALPHA_8, ColorAlphaType.PREMUL))
        bitmap.installPixels(alpha)
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    /** Single-channel 8-bit Mat of the mask at its own (reduced) resolution - see [CopyFromImage.saveCopyFromImage]. */
    internal fun toMat(): Mat {
        val mat = Mat(height, width, CvType.CV_8UC1)
        mat.put(0, 0, alpha)
        return mat
    }
}

/**
 * "Copy from image" tool (see AlignButtonsRow's "Copy from image" submenu): repairs one eye-half by
 * painting over it with the matching area of the other eye-half. The user first clicks one matching
 * point on each eye (same as ClickAlign) - that translation is only used to line the source up with
 * the target while painting, and is NOT applied to the saved photo: [saveCopyFromImage] keeps both
 * eye-halves at their original framing and only replaces the painted pixels of the target half.
 */
object CopyFromImage {
    /**
     * Offset (fraction of the eye-half's width/height) from a target-eye position to the source-eye
     * position it copies from - the two clicked points must land on each other, so a target pixel at
     * the target's own point reads the source's point.
     */
    fun sourceOffset(direction: CopyDirection, left: PointFraction, right: PointFraction): PointFraction =
        if (direction.targetIsRight) PointFraction(left.x - right.x, left.y - right.y)
        else PointFraction(right.x - left.x, right.y - left.y)

    fun saveCopyFromImage(file: File, direction: CopyDirection, left: PointFraction, right: PointFraction, mask: CopyFromImageMask): SavedFile? {
        if (mask.isEmpty) return null
        val fullMat = AutoAlign.fileToMat(file)
        val w = fullMat.width()
        val h = fullMat.height()
        val halfW = w / 2
        val offset = sourceOffset(direction, left, right)
        val sx = (offset.x * halfW).toDouble()
        val sy = (offset.y * h).toDouble()
        val targetX = if (direction.targetIsRight) halfW else 0
        val sourceX = if (direction.targetIsRight) 0 else halfW
        val target = fullMat.submat(Rect(targetX, 0, halfW, h))
        val source = fullMat.submat(Rect(sourceX, 0, halfW, h))

        // dst(x) = src(x + s): a forward translation by -s. Pixels with no source counterpart (shifted
        // in from outside the source eye) are excluded via the warped "valid" mask, so they keep the
        // target's own pixel instead of turning black.
        val translation = Mat(2, 3, CvType.CV_64F)
        translation.put(0, 0, 1.0, 0.0, -sx, 0.0, 1.0, -sy)
        val shiftedSource = Mat()
        Imgproc.warpAffine(source, shiftedSource, translation, Size(halfW.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0.0))
        val validSrc = Mat(h, halfW, CvType.CV_32FC1, Scalar(1.0))
        val valid = Mat()
        Imgproc.warpAffine(validSrc, valid, translation, Size(halfW.toDouble(), h.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar.all(0.0))
        translation.release()
        validSrc.release()

        val maskSmall = mask.toMat()
        val mask8 = Mat()
        Imgproc.resize(maskSmall, mask8, Size(halfW.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        maskSmall.release()
        val alpha = Mat()
        mask8.convertTo(alpha, CvType.CV_32F, 1.0 / 255.0)
        mask8.release()
        Core.multiply(alpha, valid, alpha)
        valid.release()
        val alpha4 = Mat()
        Core.merge(listOf(alpha, alpha, alpha, alpha), alpha4)
        alpha.release()

        // result = target + (source - target) * alpha
        val target32 = Mat()
        target.convertTo(target32, CvType.CV_32FC4)
        val source32 = Mat()
        shiftedSource.convertTo(source32, CvType.CV_32FC4)
        shiftedSource.release()
        Core.subtract(source32, target32, source32)
        Core.multiply(source32, alpha4, source32)
        Core.add(target32, source32, target32)
        source32.release()
        alpha4.release()
        target32.convertTo(target, CvType.CV_8UC4)
        target32.release()
        target.release()
        source.release()

        val saved = AutoAlign.writeAlignedResult(file, fullMat)
        fullMat.release()
        return saved
    }
}
