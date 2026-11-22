package com.pixora.gallery.classification

enum class ContentCategory {
    MOVIE,
    SERIES,
    DORAMA,
    ANIME,
    RECIPE,
    NOTE,
    SHOPPING,
    LINK,
    ADDRESS,
    EVENT,
    DOCUMENT,
    OTHER,
    ;

    val watchable: Boolean
        get() = this == MOVIE || this == SERIES || this == DORAMA || this == ANIME

    companion object {
        fun fromName(value: String?): ContentCategory =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}

enum class ExplainCode {
    EMPTY,
    LOW_SIGNAL,
    KEYWORDS,
    TITLE_FROM_YEAR,
    AMBIGUOUS,
    NO_TITLE,
    LINK,
    RECIPE,
    LIST,
    ADDRESS,
    EVENT,
    DOCUMENT,
}

data class Interpretation(
    val category: ContentCategory,
    val title: String?,
    val year: Int?,
    val confidence: Int,
    val explain: ExplainCode,
    val candidates: List<String>,
    val fields: Map<String, String>,
    val sourceText: String,
)

fun interface ContentInterpreter {
    fun interpret(rawText: String): Interpretation
}
