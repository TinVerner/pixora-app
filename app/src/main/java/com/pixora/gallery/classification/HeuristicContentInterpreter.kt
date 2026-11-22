package com.pixora.gallery.classification

/**
 * Rule-based reading of OCR text. It never invents a title: if a name is not
 * present in the text, [Interpretation.title] stays null and the full text is kept.
 * A later on-device model can implement [ContentInterpreter] and replace this class.
 */
class HeuristicContentInterpreter : ContentInterpreter {

    override fun interpret(rawText: String): Interpretation {
        val original = rawText.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (original.isBlank()) {
            return Interpretation(
                category = ContentCategory.OTHER,
                title = null,
                year = null,
                confidence = 0,
                explain = ExplainCode.EMPTY,
                candidates = emptyList(),
                fields = emptyMap(),
                sourceText = original,
            )
        }

        val lines = original.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val useful = lines.filterNot { isChromeLine(it) }
        val basis = useful.joinToString("\n").ifBlank { original }
        val urls = extractUrls(basis)
        val scores = score(basis)
        val category = chooseCategory(scores, urls, basis)
        val fields = linkedMapOf<String, String>()
        if (urls.isNotEmpty()) fields["url"] = urls.first()

        val pick = when (category) {
            ContentCategory.LINK -> TitlePick(
                title = hostOrNearbyTitle(useful, urls.firstOrNull()),
                year = null,
                candidates = emptyList(),
                explain = ExplainCode.LINK,
            )
            ContentCategory.RECIPE -> recipeTitle(useful)
            ContentCategory.SHOPPING -> listTitle(useful)
            ContentCategory.ADDRESS -> TitlePick(null, null, emptyList(), ExplainCode.ADDRESS)
            ContentCategory.EVENT -> TitlePick(eventTitle(useful), yearIn(basis), emptyList(), ExplainCode.EVENT)
            ContentCategory.DOCUMENT -> TitlePick(documentTitle(useful), null, emptyList(), ExplainCode.DOCUMENT)
            ContentCategory.MOVIE, ContentCategory.SERIES, ContentCategory.DORAMA, ContentCategory.ANIME ->
                extractWatchTitle(useful.ifEmpty { lines })
            ContentCategory.NOTE -> noteTitle(useful)
            ContentCategory.OTHER -> otherTitle(useful, basis)
        }

        if (pick.year != null) fields["year"] = pick.year.toString()
        if (category == ContentCategory.ADDRESS) {
            val address = useful.filter { looksLikeAddress(it) }.joinToString(", ")
            if (address.isNotBlank()) fields["address"] = address
        }

        val confidence = confidenceFor(category, scores, pick)
        return Interpretation(
            category = category,
            title = pick.title,
            year = pick.year,
            confidence = confidence,
            explain = pick.explain,
            candidates = pick.candidates,
            fields = fields,
            sourceText = original,
        )
    }

    private data class TitlePick(
        val title: String?,
        val year: Int?,
        val candidates: List<String>,
        val explain: ExplainCode,
    )

    private fun score(text: String): Map<ContentCategory, Int> {
        val scores = mutableMapOf<ContentCategory, Int>()
        KEYWORDS.forEach { (category, rules) ->
            var points = 0
            rules.forEach { (pattern, weight) ->
                if (pattern.containsMatchIn(text)) points += weight
            }
            if (points > 0) scores[category] = points
        }
        return scores
    }

    private fun chooseCategory(
        scores: Map<ContentCategory, Int>,
        urls: List<String>,
        text: String,
    ): ContentCategory {
        val best = scores.maxByOrNull { it.value }
        val bestScore = best?.value ?: 0
        val linkScore = scores[ContentCategory.LINK] ?: 0
        if (urls.isNotEmpty() && (linkScore >= bestScore || bestScore < 3)) {
            return ContentCategory.LINK
        }
        val dorama = scores[ContentCategory.DORAMA] ?: 0
        val anime = scores[ContentCategory.ANIME] ?: 0
        val series = scores[ContentCategory.SERIES] ?: 0
        val movie = scores[ContentCategory.MOVIE] ?: 0
        if (dorama >= 5 && dorama >= series && dorama >= movie) return ContentCategory.DORAMA
        if (anime >= 5 && anime >= series) return ContentCategory.ANIME
        if (best != null && bestScore >= 3) return best.key
        if (text.length >= 80) return ContentCategory.NOTE
        if (text.length >= 20) return ContentCategory.NOTE
        return ContentCategory.OTHER
    }

    private fun extractWatchTitle(lines: List<String>): TitlePick {
        val text = lines.joinToString("\n")
        val found = mutableListOf<ScoredTitle>()
        QUOTED.findAll(text).forEach { match ->
            val title = cleanTitle(match.groupValues[1])
            if (acceptableTitle(title)) {
                found += ScoredTitle(title, yearNear(text, match.range.first), 82)
            }
        }
        val sentences = text.split(Regex("[\\n.!?•|]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        sentences.forEach { sentence ->
            splitAlternatives(sentence).forEach { part ->
                val year = yearIn(part)
                if (isHeader(part) && year == null) return@forEach
                val body = stripWrappers(part, year)
                if (!acceptableTitle(body) || isHeader(body)) return@forEach
                val score = (if (year != null) 74 else 48) + if (body.length in 3..42) 8 else 0
                found += ScoredTitle(body, year, score)
            }
        }
        val distinct = found
            .groupBy { normalizeKey(it.title) }
            .map { (_, group) -> group.maxBy { it.score } }
            .sortedByDescending { it.score }
        if (distinct.isEmpty()) {
            return TitlePick(null, yearIn(text), emptyList(), ExplainCode.NO_TITLE)
        }
        val best = distinct.first()
        val rivals = distinct.drop(1).filter { it.score >= best.score - 8 && it.score >= 56 }
        val names = distinct.map { it.title }.distinct()
        if (rivals.isNotEmpty() || best.score < 60) {
            return TitlePick(
                title = null,
                year = distinct.firstNotNullOfOrNull { it.year },
                candidates = names.take(6),
                explain = ExplainCode.AMBIGUOUS,
            )
        }
        return TitlePick(
            title = best.title,
            year = best.year ?: yearIn(text),
            candidates = names.take(6),
            explain = if (best.year != null) ExplainCode.TITLE_FROM_YEAR else ExplainCode.KEYWORDS,
        )
    }

    private fun splitAlternatives(sentence: String): List<String> {
        if (!sentence.contains('/') && !sentence.contains('·')) return listOf(sentence)
        val parts = sentence.split('/', '·').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return listOf(sentence)
        val titled = parts.filter { acceptableTitle(stripWrappers(it, yearIn(it))) }
        return if (titled.size >= 2) parts else listOf(sentence)
    }

    private fun stripWrappers(sentence: String, year: Int?): String {
        var body = sentence
        if (year != null) {
            body = body.replace(Regex("(?<![0-9])$year(?![0-9])"), " ")
        }
        val colon = body.lastIndexOf(':')
        if (colon in 1 until body.lastIndex) {
            val left = body.substring(0, colon).trim()
            val right = body.substring(colon + 1).trim()
            if (isHeader(left) || looksLikeLabel(left)) body = right
        }
        return cleanTitle(body)
    }

    private fun recipeTitle(lines: List<String>): TitlePick {
        val explicit = lines.firstNotNullOfOrNull { line ->
            RECIPE_NAMED.find(line)?.let { cleanTitle(it.groupValues[1]) }
        }
        if (explicit != null && acceptableTitle(explicit)) {
            return TitlePick(explicit, null, listOf(explicit), ExplainCode.RECIPE)
        }
        val heading = lines.firstOrNull { line ->
            !line.contains("ингредиент", ignoreCase = true) &&
                !line.contains("ingredient", ignoreCase = true) &&
                acceptableTitle(cleanTitle(line)) &&
                !isChromeLine(line)
        }
        val title = heading?.let { cleanTitle(it) }?.takeIf { acceptableTitle(it) && !isHeader(it) }
        return TitlePick(title, null, listOfNotNull(title), ExplainCode.RECIPE)
    }

    private fun listTitle(lines: List<String>): TitlePick {
        val heading = lines.firstOrNull { isHeader(it) || it.contains("список", ignoreCase = true) }
        val title = heading?.let { cleanTitle(it) }?.takeIf { acceptableTitle(it) }
        return TitlePick(title, null, listOfNotNull(title), ExplainCode.LIST)
    }

    private fun otherTitle(lines: List<String>, basis: String): TitlePick {
        val alternatives = alternativeCandidates(lines)
        if (alternatives.size >= 2) {
            return TitlePick(null, yearIn(basis), alternatives, ExplainCode.AMBIGUOUS)
        }
        return TitlePick(null, yearIn(basis), emptyList(), ExplainCode.LOW_SIGNAL)
    }

    private fun alternativeCandidates(lines: List<String>): List<String> {
        val found = mutableListOf<String>()
        lines.forEach { line ->
            if (!line.contains('/') && !line.contains('·')) return@forEach
            val parts = line.split('/', '·')
                .map { cleanTitle(it) }
                .filter { acceptableTitle(it) && !isHeader(it) }
            if (parts.size >= 2) found += parts
        }
        return found.distinct()
    }

    private fun noteTitle(lines: List<String>): TitlePick {
        val alternatives = alternativeCandidates(lines)
        if (alternatives.size >= 2) {
            return TitlePick(null, yearIn(lines.joinToString(" ")), alternatives, ExplainCode.AMBIGUOUS)
        }
        if (lines.size < 2) return TitlePick(null, yearIn(lines.joinToString(" ")), emptyList(), ExplainCode.KEYWORDS)
        val first = cleanTitle(lines.first())
        val title = first.takeIf { acceptableTitle(it) && it.length <= 60 && !isHeader(it) }
        return TitlePick(title, null, listOfNotNull(title), ExplainCode.KEYWORDS)
    }

    private fun eventTitle(lines: List<String>): String? {
        val line = lines.firstOrNull { acceptableTitle(cleanTitle(it)) && !isChromeLine(it) } ?: return null
        return cleanTitle(line).takeIf { acceptableTitle(it) }
    }

    private fun documentTitle(lines: List<String>): String? {
        val line = lines.firstOrNull { line ->
            DOCUMENT_HINT.containsMatchIn(line) || (acceptableTitle(cleanTitle(line)) && line.length <= 80)
        } ?: return null
        return cleanTitle(line).takeIf { it.length in 3..80 }
    }

    private fun hostOrNearbyTitle(lines: List<String>, url: String?): String? {
        val nearby = lines.firstOrNull { line ->
            !line.contains("http", ignoreCase = true) && acceptableTitle(cleanTitle(line))
        }
        if (nearby != null) return cleanTitle(nearby)
        val host = url?.let { HOST.find(it)?.groupValues?.get(1) } ?: return null
        return host.removePrefix("www.").takeIf { it.isNotBlank() }
    }

    private fun confidenceFor(
        category: ContentCategory,
        scores: Map<ContentCategory, Int>,
        pick: TitlePick,
    ): Int {
        if (category == ContentCategory.OTHER && pick.title == null) return 8
        var value = 18 + (scores[category] ?: 0) * 7
        if (pick.title != null) value += 24
        if (pick.year != null) value += 8
        if (pick.candidates.size > 1 && pick.title == null) value = minOf(value, 46)
        if (category.watchable && pick.title == null) value = minOf(value, 40)
        if (category == ContentCategory.OTHER) value = minOf(value, 22)
        return value.coerceIn(0, 92)
    }

    private data class ScoredTitle(val title: String, val year: Int?, val score: Int)

    companion object {
        private val QUOTED = Regex("[«\"“]([^»\"”\\n]{2,80})[»\"”]")
        private val YEAR = Regex("(?<![0-9])(19[5-9][0-9]|20[0-3][0-9])(?![0-9])")
        private val URL = Regex("(?iu)https?://[^\\s<>]+|www\\.[^\\s<>]+")
        private val HOST = Regex("(?iu)https?://(?:www\\.)?([^/\\s]+)")
        private val RECIPE_NAMED = Regex("(?iu)(?:рецепт|recipe)\\s*[:—-]\\s*(.{2,80})")
        private val DOCUMENT_HINT = Regex("(?iu)договор|сч[её]т|акт|паспорт|invoice|receipt|квитанц")
        private val COUNT_LINE = Regex("(?iu)^[\\d\\s.,]+(?:[kкmм]|тыс\\.?)?$")
        private val TIME_LINE = Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$")
        private val HANDLE = Regex("^@[\\w.\\u0400-\\u04FF]{2,}$")
        private val HASHTAG = Regex("^[#＃][\\w\\u0400-\\u04FF]{1,40}$")

        private val CHROME = setOf(
            "для вас", "подписки", "входящие", "профиль", "главная", "лента",
            "нравится", "не нравится", "комментарий", "комментарии", "поделиться",
            "сохранить", "переслать", "подписаться", "подписка", "следить", "подписан",
            "сообщение", "сообщения", "смотреть", "ещё", "еще", "подробнее", "пропустить",
            "далее", "назад", "поиск", "интересно", "не интересно", "дуэт", "звук",
            "оригинальный звук", "подписчики", "подписчик", "войти", "регистрация",
            "for you", "following", "followers", "inbox", "profile", "like", "likes",
            "comment", "comments", "share", "save", "follow", "message", "messages",
            "watch", "more", "home", "discover", "explore", "reels", "stories", "live",
            "duet", "stitch", "tiktok", "instagram", "youtube", "shorts", "reply",
            "original sound", "add comment", "sign up", "log in", "liked",
        )

        private val TITLE_STOP = CHROME + setOf(
            "дорама", "дорамы", "фильм", "фильмы", "сериал", "сериалы", "аниме",
            "movie", "movies", "series", "anime", "drama", "k-drama",
        )

        private val KEYWORDS: Map<ContentCategory, List<Pair<Regex, Int>>> = mapOf(
            ContentCategory.DORAMA to listOf(
                Regex("(?iu)дорам\\p{L}*") to 6,
                Regex("(?iu)k[- ]?drama") to 6,
                Regex("(?iu)korean drama") to 6,
                Regex("(?iu)корейск\\p{L}*") to 2,
            ),
            ContentCategory.ANIME to listOf(
                Regex("(?iu)аниме") to 6,
                Regex("(?iu)\\banime\\b") to 6,
                Regex("(?iu)манга|манхва|манхву") to 4,
            ),
            ContentCategory.SERIES to listOf(
                Regex("(?iu)сериал\\p{L}*") to 5,
                Regex("(?iu)\\bseason\\b|\\bepisode\\b") to 4,
                Regex("(?iu)\\bсезон\\b|\\bсерия\\b") to 3,
                Regex("(?iu)tv show|netflix") to 3,
            ),
            ContentCategory.MOVIE to listOf(
                Regex("(?iu)фильм\\p{L}*") to 5,
                Regex("(?iu)\\bкино\\b") to 4,
                Regex("(?iu)\\bmovie\\b|\\bfilm\\b") to 5,
            ),
            ContentCategory.RECIPE to listOf(
                Regex("(?iu)рецепт\\p{L}*") to 6,
                Regex("(?iu)ингредиент\\p{L}*") to 5,
                Regex("(?iu)ingredient|tablespoon|teaspoon|preheat") to 4,
                Regex("(?iu)духовк|ст\\.\\s?л|ч\\.\\s?л") to 3,
            ),
            ContentCategory.SHOPPING to listOf(
                Regex("(?iu)список покупок") to 6,
                Regex("(?iu)\\bкупить\\b") to 3,
                Regex("(?iu)shopping list|grocery") to 5,
                Regex("(?iu)\\bшт\\.?\\b|\\bкг\\b") to 2,
            ),
            ContentCategory.LINK to listOf(
                Regex("(?iu)https?://|www\\.") to 6,
            ),
            ContentCategory.ADDRESS to listOf(
                Regex("(?iu)\\bул\\.?\\b|\\bулиц\\p{L}*|\\bпроспект\\b|\\bпр-т\\b|\\bпереулок\\b") to 5,
                Regex("(?iu)\\bкв\\.?\\s*\\d+|\\bд\\.?\\s*\\d+") to 3,
                Regex("(?iu)\\b\\d{6}\\b") to 2,
            ),
            ContentCategory.EVENT to listOf(
                Regex("(?iu)встреч\\p{L}*|созвон|концерт|билет") to 5,
                Regex("(?iu)\\bevent\\b|\\btomorrow at\\b") to 4,
                Regex("(?iu)\\bсегодня в\\s+\\d|\\bзавтра в\\s+\\d") to 4,
            ),
            ContentCategory.DOCUMENT to listOf(
                Regex("(?iu)договор|сч[её]т|акт выполн|паспорт|квитанц") to 5,
                Regex("(?iu)\\binvoice\\b|\\breceipt\\b") to 5,
            ),
        )

        fun isChromeLine(line: String): Boolean {
            val key = line.trim().lowercase().replace('ё', 'е')
            if (key.isEmpty()) return true
            if (key in CHROME) return true
            if (HANDLE.matches(line.trim())) return true
            if (HASHTAG.matches(line.trim())) return true
            if (TIME_LINE.matches(key)) return true
            if (COUNT_LINE.matches(key)) return true
            val hasLetter = key.any { it.isLetter() }
            val year = yearIn(line)
            if (!hasLetter && year == null) return true
            return false
        }

        fun acceptableTitle(title: String): Boolean {
            val clean = title.trim()
            if (clean.length !in 2..80) return false
            if (!clean.any { it.isLetter() }) return false
            val key = normalizeKey(clean)
            if (key in TITLE_STOP) return false
            if (key.isBlank()) return false
            if (HANDLE.matches(clean)) return false
            if (clean.count { it.isWhitespace() } > 11) return false
            return true
        }

        fun isHeader(value: String): Boolean {
            val key = value.lowercase().replace('ё', 'е')
            if (Regex("(?iu)^(топ|top|лучшие|лучшее|best)[-\\s\\d]").containsMatchIn(key)) return true
            if (key.contains("стоит посмотреть")) return true
            if (key.contains("you should watch") || key.contains("must watch")) return true
            if (key.contains("которые стоит")) return true
            if (key.contains("рекомендац")) return true
            return false
        }

        fun yearIn(text: String): Int? = YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()

        fun normalizeKey(value: String): String =
            value.lowercase()
                .replace('ё', 'е')
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()

        private fun cleanTitle(value: String): String =
            value.trim()
                .trim('"', '«', '»', '\'', '“', '”', ',', '.', ':', ';', '-', '–', '—', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun looksLikeLabel(value: String): Boolean {
            val key = normalizeKey(value)
            return key.startsWith("рецепт") || key.startsWith("recipe") || key in TITLE_STOP
        }

        private fun looksLikeAddress(line: String): Boolean =
            KEYWORDS.getValue(ContentCategory.ADDRESS).any { it.first.containsMatchIn(line) }

        private fun extractUrls(text: String): List<String> =
            URL.findAll(text).map { it.value.trimEnd('.', ',', ')', ']', '>') }.distinct().toList()

        private fun yearNear(text: String, index: Int): Int? {
            val from = (index - 12).coerceAtLeast(0)
            val to = (index + 48).coerceAtMost(text.length)
            return yearIn(text.substring(from, to))
        }
    }
}
