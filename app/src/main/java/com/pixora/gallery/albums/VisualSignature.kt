package com.pixora.gallery.albums

import com.pixora.gallery.duplicates.DHash
import kotlin.math.abs

data class Signature(
    val dHash: Long,
    val histogram: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Signature) return false
        return dHash == other.dHash && histogram.contentEquals(other.histogram)
    }

    override fun hashCode(): Int = 31 * dHash.hashCode() + histogram.contentHashCode()
}

object VisualSignature {
    fun histogram(pixels: IntArray, width: Int, height: Int): FloatArray {
        val bins = FloatArray(48)
        val counts = IntArray(16)
        if (width <= 0 || height <= 0) return bins
        for (y in 0 until height) {
            val by = (y * 4 / height).coerceIn(0, 3)
            val row = y * width
            for (x in 0 until width) {
                val bx = (x * 4 / width).coerceIn(0, 3)
                val cell = by * 4 + bx
                val color = pixels[row + x]
                bins[cell * 3] += ((color shr 16) and 0xFF) / 255f
                bins[cell * 3 + 1] += ((color shr 8) and 0xFF) / 255f
                bins[cell * 3 + 2] += (color and 0xFF) / 255f
                counts[cell]++
            }
        }
        for (cell in 0 until 16) {
            val count = counts[cell].coerceAtLeast(1).toFloat()
            bins[cell * 3] /= count
            bins[cell * 3 + 1] /= count
            bins[cell * 3 + 2] /= count
        }
        return bins
    }

    fun similarity(left: Signature, right: Signature): Float {
        val hashSim = 1f - DHash.hamming(left.dHash, right.dHash) / 64f
        val size = minOf(left.histogram.size, right.histogram.size)
        if (size == 0) return hashSim
        var diff = 0f
        for (index in 0 until size) {
            diff += abs(left.histogram[index] - right.histogram[index])
        }
        val colorSim = 1f - (diff / size).coerceIn(0f, 1f)
        return 0.55f * hashSim + 0.45f * colorSim
    }

    fun best(exemplars: List<Signature>, candidate: Signature): Float {
        if (exemplars.isEmpty()) return 0f
        return exemplars.maxOf { similarity(it, candidate) }
    }

    fun encode(histogram: FloatArray): String = histogram.joinToString(",") { "%.3f".format(it) }

    fun decode(stored: String?): FloatArray {
        if (stored.isNullOrBlank()) return FloatArray(48)
        val parts = stored.split(',')
        val values = FloatArray(48)
        parts.forEachIndexed { index, part ->
            if (index < values.size) values[index] = part.toFloatOrNull() ?: 0f
        }
        return values
    }
}
