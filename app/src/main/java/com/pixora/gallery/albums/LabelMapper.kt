package com.pixora.gallery.albums

object AlbumLabels {
    const val SCREENSHOTS = "SCREENSHOTS"
    const val ANIMALS = "ANIMALS"
    const val CATS = "CATS"
    const val DOGS = "DOGS"
    const val PEOPLE = "PEOPLE"
    const val NATURE = "NATURE"
    const val FOOD = "FOOD"
    const val DOCUMENTS = "DOCUMENTS"
    const val TRAVEL = "TRAVEL"

    val system = listOf(SCREENSHOTS, CATS, DOGS, ANIMALS, PEOPLE, FOOD, NATURE, TRAVEL, DOCUMENTS)
}

object LabelMapper {
    private val rules = listOf(
        Rule(setOf("cat", "kitten"), setOf(AlbumLabels.CATS, AlbumLabels.ANIMALS)),
        Rule(setOf("dog", "puppy"), setOf(AlbumLabels.DOGS, AlbumLabels.ANIMALS)),
        Rule(setOf("animal", "bird", "horse", "fish", "pet", "wildlife"), setOf(AlbumLabels.ANIMALS)),
        Rule(setOf("person", "people", "selfie", "smile", "face", "human", "crowd", "portrait"), setOf(AlbumLabels.PEOPLE)),
        Rule(setOf("food", "dish", "cuisine", "meal", "dessert", "fruit", "pizza", "cake", "snack"), setOf(AlbumLabels.FOOD)),
        Rule(
            setOf("nature", "mountain", "sky", "beach", "sea", "ocean", "forest", "tree", "flower", "plant", "landscape", "sunset", "lake", "river", "snow"),
            setOf(AlbumLabels.NATURE),
        ),
        Rule(setOf("document", "paper", "receipt", "menu", "text"), setOf(AlbumLabels.DOCUMENTS)),
        Rule(setOf("travel", "tourism", "monument", "temple", "castle", "cityscape"), setOf(AlbumLabels.TRAVEL)),
    )

    fun map(labels: List<Pair<String, Float>>, threshold: Float = 0.60f): Set<String> {
        val accepted = labels.filter { it.second >= threshold }.map { it.first.lowercase() }
        val result = mutableSetOf<String>()
        rules.forEach { rule ->
            if (accepted.any { label -> rule.needles.any { needle -> label.contains(needle) } }) {
                result += rule.outputs
            }
        }
        return result
    }

    fun format(labels: Set<String>): String =
        if (labels.isEmpty()) "" else labels.sorted().joinToString(separator = ",", prefix = ",", postfix = ",")

    fun parse(stored: String?): Set<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private data class Rule(val needles: Set<String>, val outputs: Set<String>)
}
