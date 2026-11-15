package app.hoot.engine

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin background remover porting Windows Hoot BackgroundRemover.cs.
 * Turns the plain or paper backdrop of signatures, stamps, logos, scans and photos transparent.
 *
 * 1. Border colour estimation: Per-channel median of border pixels.
 * 2. Unmixing: pixel = alpha * ink + (1 - alpha) * backdrop to preserve smooth anti-aliased edges.
 * 3. Specks removal: Clears sensor noise and paper grain.
 */
object BackgroundRemover {

    private const val MAX_CUTOFF = 0.48f
    private const val MIN_SOFT_BAND = 0.15f
    private const val MIN_HEADROOM = 96f
    private const val MIN_SPECK_SIZE = 32

    data class Rgb(val r: Float, val g: Float, val b: Float)

    fun apply(source: Bitmap, tolerance: Int = 25, keepEnclosed: Boolean = false): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 2 || height < 2) return source

        // Ensure 32-bit ARGB_8888 bitmap
        val output = if (source.config == Bitmap.Config.ARGB_8888 && source.isMutable) {
            source
        } else {
            source.copy(Bitmap.Config.ARGB_8888, true)
        }

        val pixels = IntArray(width * height)
        output.getPixels(pixels, 0, width, 0, 0, width, height)

        val border = estimateBorderColor(pixels, width, height) ?: return output

        val cutoff = (tolerance.coerceIn(0, 100) / 100f) * MAX_CUTOFF
        val distinct = min(1f, cutoff + MIN_SOFT_BAND)

        val raw = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val p = pixels[i]
                val a = Color.alpha(p)
                if (a < 128) {
                    raw[i] = 0f
                    continue
                }
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                val opR = computeOpacity(r, border.r)
                val opG = computeOpacity(g, border.g)
                val opB = computeOpacity(b, border.b)

                val opacity = max(opR, max(opG, opB))
                raw[i] = opacity.coerceIn(0f, 1f)
            }
        }

        val full = computeInkOpacity(raw, cutoff)

        // Process pixels in place
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val p = pixels[i]
                val origAlpha = Color.alpha(p)
                if (origAlpha == 0) continue

                val rawOp = raw[i]
                var alpha = if (full > cutoff) {
                    ((rawOp - cutoff) / (full - cutoff)).coerceIn(0f, 1f)
                } else {
                    if (rawOp > cutoff) 1f else 0f
                }

                if (alpha <= 0.001f) {
                    pixels[i] = 0 // Fully transparent
                } else {
                    var r = Color.red(p)
                    var g = Color.green(p)
                    var b = Color.blue(p)

                    if (alpha < 1f) {
                        r = unmix(r, border.r, alpha)
                        g = unmix(g, border.g, alpha)
                        b = unmix(b, border.b, alpha)
                    }

                    val finalAlpha = (alpha * origAlpha).roundToInt().coerceIn(0, 255)
                    pixels[i] = Color.argb(finalAlpha, r, g, b)
                }
            }
        }

        if (width >= MIN_SPECK_SIZE && height >= MIN_SPECK_SIZE) {
            removeSpecks(pixels, width, height)
        }

        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    private fun computeOpacity(value: Int, backdrop: Float): Float {
        val d = value - backdrop
        return if (d >= 0) {
            d / max(255f - backdrop, MIN_HEADROOM)
        } else {
            -d / max(backdrop, MIN_HEADROOM)
        }
    }

    private fun unmix(value: Int, backdrop: Float, alpha: Float): Int {
        val res = backdrop + (value - backdrop) / alpha
        return res.roundToInt().coerceIn(0, 255)
    }

    private fun computeInkOpacity(raw: FloatArray, cutoff: Float): Float {
        val floor = min(1f, cutoff + MIN_SOFT_BAND)
        val histogram = IntArray(256)
        var total = 0
        for (op in raw) {
            if (op < floor) continue
            val bin = (op * 255f).roundToInt().coerceIn(0, 255)
            histogram[bin]++
            total++
        }
        if (total == 0) return floor
        var seen = 0
        for (bin in histogram.indices) {
            seen += histogram[bin]
            if (seen * 4 >= total * 3) {
                return max(floor, bin / 255f)
            }
        }
        return 1f
    }

    private fun estimateBorderColor(pixels: IntArray, width: Int, height: Int): Rgb? {
        val rList = ArrayList<Int>()
        val gList = ArrayList<Int>()
        val bList = ArrayList<Int>()
        var all = 0

        fun sample(x: Int, y: Int) {
            all++
            val p = pixels[y * width + x]
            if (Color.alpha(p) >= 128) {
                rList.add(Color.red(p))
                gList.add(Color.green(p))
                bList.add(Color.blue(p))
            }
        }

        for (x in 0 until width) {
            sample(x, 0)
            sample(x, height - 1)
        }
        for (y in 1 until height - 1) {
            sample(0, y)
            sample(width - 1, y)
        }

        if (rList.size * 2 < all) return null // mostly transparent border

        rList.sort()
        gList.sort()
        bList.sort()

        val mid = rList.size / 2
        return Rgb(
            r = rList[mid].toFloat(),
            g = gList[mid].toFloat(),
            b = bList[mid].toFloat()
        )
    }

    private fun removeSpecks(pixels: IntArray, width: Int, height: Int) {
        val specks = ArrayList<Int>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (Color.alpha(pixels[idx]) == 0) continue

                var neighbours = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until width && ny in 0 until height) {
                            if (Color.alpha(pixels[ny * width + nx]) > 0) {
                                neighbours++
                            }
                        }
                    }
                }
                if (neighbours <= 1) {
                    specks.add(idx)
                }
            }
        }
        for (idx in specks) {
            pixels[idx] = 0
        }
    }
}
