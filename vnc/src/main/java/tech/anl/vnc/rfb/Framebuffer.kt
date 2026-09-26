package tech.anl.vnc.rfb

/**
 * The remote desktop as a mutable array of ARGB_8888 ints. Written by the RFB
 * reader thread and read by the UI; every access takes the instance lock, so
 * callers that copy pixels out should do so inside [withPixels].
 */
class Framebuffer(width: Int = 0, height: Int = 0) {
    @Volatile
    var width: Int = width
        private set

    @Volatile
    var height: Int = height
        private set

    private var pixels = IntArray(width * height) { OPAQUE_BLACK }

    @Synchronized
    fun resize(newWidth: Int, newHeight: Int) {
        if (newWidth == width && newHeight == height) return
        val next = IntArray(newWidth * newHeight) { OPAQUE_BLACK }
        val cw = minOf(width, newWidth)
        val ch = minOf(height, newHeight)
        for (row in 0 until ch) System.arraycopy(pixels, row * width, next, row * newWidth, cw)
        pixels = next
        width = newWidth
        height = newHeight
    }

    @Synchronized
    fun getPixel(x: Int, y: Int): Int = pixels[y * width + x]

    /** Runs [block] with the backing array, its width and height, under the lock. */
    @Synchronized
    fun <T> withPixels(block: (pixels: IntArray, width: Int, height: Int) -> T): T =
        block(pixels, width, height)

    @Synchronized
    fun fillRect(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val x0 = maxOf(0, x)
        val y0 = maxOf(0, y)
        val x1 = minOf(width, x + w)
        val y1 = minOf(height, y + h)
        if (x0 >= x1 || y0 >= y1) return
        for (row in y0 until y1) {
            val base = row * width
            java.util.Arrays.fill(pixels, base + x0, base + x1, color)
        }
    }

    /** Copies a [w]x[h] block from [src] (row stride [srcStride]) to ([x], [y]). */
    @Synchronized
    fun putRect(x: Int, y: Int, w: Int, h: Int, src: IntArray, srcOffset: Int = 0, srcStride: Int = w) {
        val x0 = maxOf(0, x)
        val y0 = maxOf(0, y)
        val x1 = minOf(width, x + w)
        val y1 = minOf(height, y + h)
        if (x0 >= x1 || y0 >= y1) return
        for (row in y0 until y1) {
            System.arraycopy(
                src, srcOffset + (row - y) * srcStride + (x0 - x),
                pixels, row * width + x0, x1 - x0
            )
        }
    }

    /** CopyRect: moves a block within the framebuffer, handling overlap. */
    @Synchronized
    fun copyRect(srcX: Int, srcY: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (srcX < 0 || srcY < 0 || x < 0 || y < 0) return
        if (srcX + w > width || x + w > width || srcY + h > height || y + h > height) return
        if (y > srcY) {
            for (row in h - 1 downTo 0) {
                System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (y + row) * width + x, w)
            }
        } else {
            for (row in 0 until h) {
                System.arraycopy(pixels, (srcY + row) * width + srcX, pixels, (y + row) * width + x, w)
            }
        }
    }

    companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}

/** A cursor shape sent by the server, ARGB pixels with transparency from the mask. */
class RemoteCursor(
    val width: Int,
    val height: Int,
    val hotX: Int,
    val hotY: Int,
    val pixels: IntArray
)
