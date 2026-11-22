package com.pixora.gallery.duplicates

import kotlin.math.abs
import kotlin.math.ln

object QualityScorer {
    const val BLURRY_SHARPNESS = 8.0

    fun score(sharpness: Double, width: Int, height: Int, exposure: Double, sizeBytes: Long): Double {
        val sharpNorm = (sharpness / (sharpness + 40.0)).coerceIn(0.0, 1.0)
        val pixels = (width.toDouble() * height.toDouble()).coerceAtLeast(1.0)
        val resNorm = (ln(pixels) / ln(12_000_000.0)).coerceIn(0.0, 1.15)
        val sizeNorm = (ln(sizeBytes.coerceAtLeast(1L).toDouble()) / ln(8_000_000.0)).coerceIn(0.0, 1.0)
        val exposureNorm = exposure.coerceIn(0.0, 1.0)
        return sharpNorm * 0.52 + exposureNorm * 0.23 + resNorm * 0.20 + sizeNorm * 0.05
    }

    fun exposure(meanLuma: Double, clipFraction: Double): Double {
        val center = 1.0 - (abs(meanLuma - 135.0) / 135.0)
        val clipped = (clipFraction * 1.4).coerceAtMost(0.7)
        return (center - clipped).coerceIn(0.0, 1.0)
    }

    fun isProbablyBlurry(sharpness: Double?): Boolean =
        sharpness != null && sharpness < BLURRY_SHARPNESS

    fun laplacianVariance(luma: Array<DoubleArray>): Double {
        val height = luma.size
        val width = luma.firstOrNull()?.size ?: return 0.0
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val value = -4.0 * luma[y][x] + luma[y - 1][x] + luma[y + 1][x] + luma[y][x - 1] + luma[y][x + 1]
                sum += value
                sumSq += value * value
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return (sumSq / count - mean * mean).coerceAtLeast(0.0)
    }
}
