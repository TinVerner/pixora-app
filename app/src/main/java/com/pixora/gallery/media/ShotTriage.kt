package com.pixora.gallery.media

import com.pixora.gallery.classification.ContentCategory

/**
 * Splits screenshots before and after OCR.
 * The app name is taken from the file name Samsung writes
 * (`Screenshot_дата_время_Приложение.jpg` or a package name).
 * Chats and games stay out of the default queue.
 */
enum class ShotOrigin {
    TIKTOK,
    INSTAGRAM,
    YOUTUBE,
    BROWSER,
    OTHER,
    CHAT,
    GAME,
    ;

    val worthReading: Boolean
        get() = this == TIKTOK || this == INSTAGRAM || this == YOUTUBE || this == BROWSER || this == OTHER
}

enum class ReviewLane {
    TITLES,
    SOCIAL,
    REST,
    CHAT,
    GAME,
}

object ShotTriage {
    fun origin(displayName: String): ShotOrigin = classify(appToken(displayName))

    fun lane(
        origin: ShotOrigin,
        category: ContentCategory,
        title: String?,
        candidates: List<String>,
    ): ReviewLane {
        if (origin == ShotOrigin.CHAT) return ReviewLane.CHAT
        if (origin == ShotOrigin.GAME && !category.watchable) return ReviewLane.GAME
        val named = !title.isNullOrBlank() || candidates.any { it.isNotBlank() }
        if (named && (category.watchable || category == ContentCategory.RECIPE)) return ReviewLane.TITLES
        if (origin == ShotOrigin.TIKTOK || origin == ShotOrigin.INSTAGRAM || origin == ShotOrigin.YOUTUBE || origin == ShotOrigin.BROWSER) {
            return ReviewLane.SOCIAL
        }
        return ReviewLane.REST
    }

    fun titleRank(category: ContentCategory): Int = when (category) {
        ContentCategory.DORAMA -> 0
        ContentCategory.SERIES -> 1
        ContentCategory.ANIME -> 2
        ContentCategory.MOVIE -> 3
        ContentCategory.RECIPE -> 4
        else -> 5
    }

    private fun appToken(displayName: String): String {
        val base = displayName.substringBeforeLast('.').trim()
        SAMSUNG_APP.find(base)?.let { return it.groupValues[1].trim() }
        PACKAGE_TAIL.find(base)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun classify(token: String): ShotOrigin {
        if (token.isBlank()) return ShotOrigin.OTHER
        val key = token.lowercase()
        if (containsAny(key, SOCIAL)) return socialKind(key)
        if (containsAny(key, BROWSERS)) return ShotOrigin.BROWSER
        if (containsAny(key, CHATS)) return ShotOrigin.CHAT
        if (containsAny(key, GAMES)) return ShotOrigin.GAME
        if (PACKAGE.containsMatchIn(key)) return ShotOrigin.GAME
        return ShotOrigin.OTHER
    }

    private fun socialKind(key: String): ShotOrigin = when {
        containsAny(key, TIKTOK) -> ShotOrigin.TIKTOK
        containsAny(key, INSTAGRAM) -> ShotOrigin.INSTAGRAM
        containsAny(key, YOUTUBE) -> ShotOrigin.YOUTUBE
        else -> ShotOrigin.OTHER
    }

    private fun containsAny(key: String, hints: List<String>): Boolean = hints.any { key.contains(it) }

    private val SAMSUNG_APP = Regex("(?i)^screenshot[_ -]\\d{8}[_ -]\\d{6}[_ -](.+)$")
    private val PACKAGE_TAIL = Regex("(?i)[_-]((?:com|org|net)\\.[a-z0-9_.]+)$")
    private val PACKAGE = Regex("^(?:com|org|net)\\.[a-z0-9_.]+$")

    private val TIKTOK = listOf("tiktok", "tik tok", "musically", "aweme", "trill")
    private val INSTAGRAM = listOf("instagram")
    private val YOUTUBE = listOf("youtube", "youtu")
    private val SOCIAL = TIKTOK + INSTAGRAM + YOUTUBE
    private val BROWSERS = listOf(
        "chrome", "firefox", "opera", "brave", "yandex", "edge", "duckduckgo",
        "samsung internet", "sbrowser", "браузер",
    )
    private val CHATS = listOf(
        "ayugram", "telegram", "whatsapp", "viber", "discord", "messenger",
        "сообщения", "messages", "imo", "wechat", "kakao", "signal", "вконтакте", "vkontakte",
    )
    private val GAMES = listOf(
        "genshin", "mihoyo", "hoyoverse", "honkai", "zenless", "mobile legends", "mlbb",
        "moonton", "pubg", "roblox", "brawl", "clash", "minecraft", "freefire", "free fire",
        "supercell", "garena", "лего", "lego", "pokemon", "raid", "stumble",
    )
}
