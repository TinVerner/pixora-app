package com.pixora.gallery.media

object ScreenshotDetector {
    private val pathHints = listOf(
        "screenshots",
        "screenshot",
        "screencapture",
        "screen capture",
        "screen-capture",
        "скрины",
        "скриншот",
    )

    private val nameHints = listOf(
        "screenshot",
        "screen shot",
        "screencap",
        "screen_",
        "скриншот",
        "снимок экрана",
        "снимок_экрана",
    )

    fun isScreenshot(relativePath: String?, displayName: String?, bucketName: String?): Boolean {
        val path = (relativePath ?: "").lowercase().replace('\\', '/')
        val name = (displayName ?: "").lowercase()
        val bucket = (bucketName ?: "").lowercase()
        if (pathHints.any { path.contains(it) || bucket.contains(it) }) return true
        if (nameHints.any { name.contains(it) }) return true
        return false
    }
}
