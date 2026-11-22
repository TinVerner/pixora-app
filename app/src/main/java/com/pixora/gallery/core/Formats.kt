package com.pixora.gallery.core

import java.util.Locale
import kotlin.math.abs

fun formatBytes(bytes: Long): String {
    val sign = if (bytes < 0) "-" else ""
    val value = abs(bytes).toDouble()
    val locale = Locale.getDefault()
    return when {
        value < 1024 -> "$sign${abs(bytes)} Б"
        value < 1024 * 1024 -> sign + String.format(locale, "%.1f КБ", value / 1024.0)
        value < 1024 * 1024 * 1024 -> sign + String.format(locale, "%.1f МБ", value / (1024.0 * 1024.0))
        else -> sign + String.format(locale, "%.2f ГБ", value / (1024.0 * 1024.0 * 1024.0))
    }
}

fun Long.asIds(): List<Long> =
    if (this == 0L) emptyList() else listOf(this)

fun parseIds(stored: String?): List<Long> =
    stored.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

fun formatIds(ids: Collection<Long>): String = ids.joinToString(",")
