package com.example.infrastructure.content

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Decodes JPEG/PNG bytes into a CHW float tensor matching the input
 * convention of ImageNet-pretrained ViT models (DINOv3 included).
 *
 * Pipeline:
 *  1. `ImageIO` decode — raises [IllegalArgumentException] if the bytes
 *     are not a recognised image format. Never crashes the JVM.
 *  2. Lanczos-3 resample directly to 224×224 (no centre-crop). Aspect
 *     ratio is intentionally distorted: Kurai indexes open-world content
 *     with arbitrary composition, and centre-cropping discards
 *     on-the-edge subjects (faces in portraits, full-body characters in
 *     landscapes) and biases every embedding toward the centre region.
 *     Direct resize is what the Python ML side of the project uses; we
 *     match it byte-for-byte semantics (Pillow's LANCZOS = Lanczos-3) so
 *     embeddings produced here live in the same vector space as those
 *     produced offline.
 *  3. Normalise per channel: `(pixel/255 − mean) / std` with ImageNet
 *     statistics.
 *  4. Reorder HWC → CHW into a flat `FloatArray(3·224·224)` for ONNX.
 *
 * Pure JDK; libvips remains a Post-MVP option if `ImageIO` becomes a
 * throughput bottleneck (see Out-of-scope in the wave plan).
 */
class ImagePreprocessor {
    /**
     * @throws IllegalArgumentException if [imageBytes] does not decode as
     *   a supported image format.
     */
    suspend fun preprocess(imageBytes: ByteArray): FloatArray =
        withContext(Dispatchers.IO) {
            val decoded = decode(imageBytes)
            val resized = lanczos3Resize(decoded, TARGET_SIZE, TARGET_SIZE)
            normalizeToChw(resized)
        }

    private fun decode(bytes: ByteArray): BufferedImage =
        ByteArrayInputStream(bytes).use { stream ->
            ImageIO.read(stream)
                ?: throw IllegalArgumentException("Bytes do not decode as a supported image format")
        }

    /**
     * Output of the resize stage: three byte planes (each in [0, 255]).
     * Pillow quantises the intermediate horizontal-pass buffer back to
     * uint8 before the vertical pass, so we mirror that exactly.
     */
    private data class ResampledImage(
        val width: Int,
        val height: Int,
        val r: ByteArray,
        val g: ByteArray,
        val b: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ResampledImage

            if (width != other.width) return false
            if (height != other.height) return false
            if (!r.contentEquals(other.r)) return false
            if (!g.contentEquals(other.g)) return false
            if (!b.contentEquals(other.b)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = width
            result = 31 * result + height
            result = 31 * result + r.contentHashCode()
            result = 31 * result + g.contentHashCode()
            result = 31 * result + b.contentHashCode()
            return result
        }
    }

    /**
     * Lanczos-3 resize, byte-for-byte compatible with Pillow's
     * `Image.resize(..., LANCZOS)` for 8-bit RGB inputs.
     *
     * Two key Pillow-specific details (see `src/libImaging/Resample.c`):
     *  1. After the horizontal pass we round + clamp to `uint8` — the
     *     vertical pass starts from quantised values, not from the raw
     *     float accumulators. Skipping this step produces ~5/255 drift.
     *  2. Bounds use C-style truncation rounding `(int)(v + 0.5)`, with
     *     out-of-range taps clipped by shrinking the bound rather than
     *     replicating border pixels.
     *
     * We stay in `double` for the accumulators (Pillow uses 22-bit
     * fixed-point for SIMD; double-precision matches it within the same
     * rounding rules).
     */
    private fun lanczos3Resize(
        src: BufferedImage,
        dstW: Int,
        dstH: Int,
    ): ResampledImage {
        val srcW = src.width
        val srcH = src.height
        val srcPixels = src.getRGB(0, 0, srcW, srcH, null, 0, srcW)

        // Horizontal pass: srcW → dstW for each of srcH rows. Output is
        // already quantised to uint8 (stored as ByteArray; Java bytes are
        // signed, treat with `& 0xFF`).
        val horizR = ByteArray(dstW * srcH)
        val horizG = ByteArray(dstW * srcH)
        val horizB = ByteArray(dstW * srcH)
        val hContrib = computeContributions(srcW, dstW)
        for (y in 0 until srcH) {
            val srcRowOffset = y * srcW
            val dstRowOffset = y * dstW
            for (x in 0 until dstW) {
                val taps = hContrib[x].indices
                val weights = hContrib[x].weights
                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                for (k in taps.indices) {
                    val px = srcPixels[srcRowOffset + taps[k]]
                    val w = weights[k].toDouble()
                    rSum += ((px shr 16) and 0xFF) * w
                    gSum += ((px shr 8) and 0xFF) * w
                    bSum += (px and 0xFF) * w
                }
                horizR[dstRowOffset + x] = clamp255ToByte(rSum)
                horizG[dstRowOffset + x] = clamp255ToByte(gSum)
                horizB[dstRowOffset + x] = clamp255ToByte(bSum)
            }
        }

        // Vertical pass: srcH → dstH for each of dstW columns.
        val outR = ByteArray(dstW * dstH)
        val outG = ByteArray(dstW * dstH)
        val outB = ByteArray(dstW * dstH)
        val vContrib = computeContributions(srcH, dstH)
        for (y in 0 until dstH) {
            val taps = vContrib[y].indices
            val weights = vContrib[y].weights
            for (x in 0 until dstW) {
                var rSum = 0.0
                var gSum = 0.0
                var bSum = 0.0
                for (k in taps.indices) {
                    val srcRowOffset = taps[k] * dstW
                    val w = weights[k].toDouble()
                    rSum += (horizR[srcRowOffset + x].toInt() and 0xFF) * w
                    gSum += (horizG[srcRowOffset + x].toInt() and 0xFF) * w
                    bSum += (horizB[srcRowOffset + x].toInt() and 0xFF) * w
                }
                val o = y * dstW + x
                outR[o] = clamp255ToByte(rSum)
                outG[o] = clamp255ToByte(gSum)
                outB[o] = clamp255ToByte(bSum)
            }
        }

        return ResampledImage(dstW, dstH, outR, outG, outB)
    }

    /**
     * Per-output-pixel filter taps: which source-pixel indices contribute
     * to it and with what (normalised) weights. Identical to Pillow's
     * `_imaging.c` resample logic.
     *
     * On downscale (`srcSize > dstSize`) the kernel widens by `1/scale`
     * to preserve the anti-aliasing properties of Lanczos.
     */
    private data class Contribution(
        val indices: IntArray,
        val weights: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Contribution

            if (!indices.contentEquals(other.indices)) return false
            if (!weights.contentEquals(other.weights)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = indices.contentHashCode()
            result = 31 * result + weights.contentHashCode()
            return result
        }
    }

    private fun computeContributions(
        srcSize: Int,
        dstSize: Int,
    ): Array<Contribution> {
        // Mirrors Pillow's `precompute_coeffs` in src/libImaging/Resample.c
        // (commit-stable since 7.x). Notable details vs textbook Lanczos:
        //  - Bounds are rounded (`(int)(v + 0.5)`), not floored/ceiled.
        //  - Out-of-source taps are clipped by truncating the bound, not
        //    by replicating border pixels.
        //  - Weights normalise per output pixel so they sum to 1 even when
        //    the bound is truncated near the image edges.
        val scale = srcSize.toDouble() / dstSize
        val filterScale = max(1.0, scale)
        val support = LANCZOS_A * filterScale
        val invFilterScale = 1.0 / filterScale
        val out = Array(dstSize) { Contribution(IntArray(0), FloatArray(0)) }
        for (i in 0 until dstSize) {
            val center = (i + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > srcSize) xmax = srcSize
            val length = xmax - xmin
            val taps = IntArray(length)
            val weights = DoubleArray(length)
            var sum = 0.0
            for (k in 0 until length) {
                val srcIdx = xmin + k
                taps[k] = srcIdx
                val w = lanczos((srcIdx - center + 0.5) * invFilterScale)
                weights[k] = w
                sum += w
            }
            val normalized = FloatArray(length)
            if (sum != 0.0) {
                for (k in 0 until length) {
                    normalized[k] = (weights[k] / sum).toFloat()
                }
            }
            out[i] = Contribution(taps, normalized)
        }
        return out
    }

    /** Lanczos kernel with a = [LANCZOS_A]. */
    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (x <= -LANCZOS_A || x >= LANCZOS_A) return 0.0
        val pix = PI * x
        return LANCZOS_A * sin(pix) * sin(pix / LANCZOS_A) / (pix * pix)
    }

    private fun clamp255ToByte(v: Double): Byte {
        // Round-half-up matches Pillow's int(0.5 + ...) for positives.
        // Negatives are clipped to 0 and never use the negative branch.
        val rounded =
            when {
                v < 0.0 -> 0
                v > 255.0 -> 255
                else -> (v + 0.5).toInt()
            }
        return rounded.toByte()
    }

    /**
     * Converts a [ResampledImage] (3 byte planes, [0,255] each) into a
     * flat `FloatArray(3·H·W)` laid out as `[R-plane, G-plane, B-plane]`,
     * each plane in row-major order. Per-pixel:
     *
     *   value = (channel/255 − mean_c) / std_c
     */
    private fun normalizeToChw(img: ResampledImage): FloatArray {
        val planeSize = img.width * img.height
        val out = FloatArray(3 * planeSize)
        for (i in 0 until planeSize) {
            val r = (img.r[i].toInt() and 0xFF) / 255f
            val g = (img.g[i].toInt() and 0xFF) / 255f
            val b = (img.b[i].toInt() and 0xFF) / 255f
            out[i] = (r - MEAN_R) / STD_R
            out[planeSize + i] = (g - MEAN_G) / STD_G
            out[2 * planeSize + i] = (b - MEAN_B) / STD_B
        }
        return out
    }

    companion object {
        const val TARGET_SIZE: Int = 224

        // Lanczos kernel radius. Pillow's LANCZOS uses a = 3.
        private const val LANCZOS_A: Double = 3.0

        // ImageNet RGB statistics (PyTorch/HuggingFace convention).
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f
    }
}
