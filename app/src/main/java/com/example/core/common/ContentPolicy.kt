package com.example.core.common

import com.example.domain.models.ContentRating

/** Conservative metadata-based categorization. NOT an age-verification or moderation service. */
object ContentPolicy {
    private val adult = setOf("adult", "18+", "hentai", "smut", "explicit", "nsfw", "للبالغين", "إباحي")
    private val mature = setOf("mature", "violence", "gore", "دموي", "عنيف")
    private val teen = setOf("teen", "shounen", "shoujo", "مراهقين")

    fun classify(tags: List<String>, declared: ContentRating = ContentRating.GENERAL): ContentRating {
        if (declared != ContentRating.GENERAL) return declared
        val normalized = tags.flatMap { it.lowercase().split(Regex("[^\\p{L}0-9+]+")) } +
            tags.map { it.lowercase().trim() }
        return when {
            normalized.any { it in adult } -> ContentRating.ADULT_18_PLUS
            normalized.any { it in mature } -> ContentRating.MATURE
            normalized.any { it in teen } -> ContentRating.TEEN
            else -> ContentRating.GENERAL
        }
    }
}
