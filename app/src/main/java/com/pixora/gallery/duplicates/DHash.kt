package com.pixora.gallery.duplicates

object DHash {
    fun compute(pixels: IntArray, width: Int, height: Int): Long {
        require(width > 1 && height > 1)
        require(pixels.size >= width * height)
        val gridW = 9
        val gridH = 8
        val gray = Array(gridH) { DoubleArray(gridW) }
        for (y in 0 until gridH) {
            val sy = (((y + 0.5) * height) / gridH).toInt().coerceIn(0, height - 1)
            for (x in 0 until gridW) {
                val sx = (((x + 0.5) * width) / gridW).toInt().coerceIn(0, width - 1)
                gray[y][x] = luminance(pixels[sy * width + sx])
            }
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until gridH) {
            for (x in 0 until gridW - 1) {
                if (gray[y][x] > gray[y][x + 1]) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hamming(left: Long, right: Long): Int = java.lang.Long.bitCount(left xor right)

    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
