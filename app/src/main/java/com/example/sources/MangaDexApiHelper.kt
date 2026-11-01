package com.example.sources

import com.example.domain.models.MangaChapter
import com.example.domain.models.MangaDetails
import com.example.domain.models.MangaPage
import com.example.domain.models.MangaSummary
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.Locale

/**
 * Pure JVM + Android compatible JSON parser for Official MangaDex REST API v5 responses.
 * Uses Square Moshi's streaming JsonReader (already in project dependencies) instead of
 * android.jar's stubbed org.json.JSONObject, ensuring identical execution in Android runtime
 * and local JVM unit tests.
 */
object MangaDexApiHelper {

    fun parseMangaListResponse(
        jsonString: String,
        sourceId: String = "mangadex"
    ): List<MangaSummary> {
        if (jsonString.isBlank()) return emptyList()
        val results = mutableListOf<MangaSummary>()

        Buffer().writeUtf8(jsonString).use { buffer ->
            JsonReader.of(buffer).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "data" -> {
                            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    parseSingleMangaObject(reader, sourceId)?.let { results.add(it.first) }
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return results
    }

    fun parseMangaSingleDetailsResponse(
        jsonString: String,
        sourceId: String = "mangadex",
        chapters: List<MangaChapter> = emptyList()
    ): MangaDetails? {
        if (jsonString.isBlank()) return null
        var parsedPair: Pair<MangaSummary, ParsedExtraMetadata>? = null

        Buffer().writeUtf8(jsonString).use { buffer ->
            JsonReader.of(buffer).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "data" -> {
                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                parsedPair = parseSingleMangaObject(reader, sourceId)
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        val (summary, extra) = parsedPair ?: return null
        return MangaDetails(
            id = summary.id,
            sourceId = sourceId,
            title = summary.title,
            alternativeTitles = extra.altTitles,
            coverUrl = summary.coverUrl,
            description = extra.description,
            author = extra.author,
            artist = extra.artist,
            status = summary.status,
            genres = summary.genres,
            rating = summary.rating,
            chapters = chapters
        )
    }

    data class ParsedExtraMetadata(
        val description: String = "",
        val altTitles: List<String> = emptyList(),
        val author: String? = null,
        val artist: String? = null
    )

    private fun parseSingleMangaObject(
        reader: JsonReader,
        sourceId: String
    ): Pair<MangaSummary, ParsedExtraMetadata>? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }

        var mdId = ""
        var primaryTitle = ""
        val altTitles = mutableListOf<String>()
        var description = ""
        var status: String? = null
        var ratingClassification = com.example.domain.models.ContentRating.GENERAL
        var lastChapter: String? = null
        val tags = mutableListOf<String>()
        var coverFileName: String? = null
        var authorName: String? = null
        var artistName: String? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> mdId = reader.nextStringOrEmpty()
                "attributes" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "title" -> {
                                    val localized = readLocalizedStringMap(reader)
                                    primaryTitle = localized["ar"]
                                        ?: localized["en"]
                                        ?: localized["ko-ro"]
                                        ?: localized["ja-ro"]
                                        ?: localized.values.firstOrNull { it.isNotBlank() }
                                        ?: ""
                                }
                                "altTitles" -> {
                                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            val map = readLocalizedStringMap(reader)
                                            map.values.filter { it.isNotBlank() }.forEach { alt ->
                                                if (altTitles.size < 6 && !altTitles.contains(alt)) {
                                                    altTitles.add(alt)
                                                }
                                            }
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                "description" -> {
                                    val descMap = readLocalizedStringMap(reader)
                                    description = descMap["ar"]
                                        ?: descMap["en"]
                                        ?: descMap.values.firstOrNull { it.isNotBlank() }
                                        ?: ""
                                }
                                "status" -> {
                                    val rawStatus = reader.nextStringOrEmpty()
                                    status = when (rawStatus.lowercase(Locale.ROOT)) {
                                        "completed" -> "Completed"
                                        "ongoing" -> "OnGoing"
                                        "hiatus" -> "Hiatus"
                                        else -> null
                                    }
                                }
                                "contentRating" -> {
                                    ratingClassification = when (reader.nextStringOrEmpty().lowercase(Locale.ROOT)) {
                                        "pornographic", "erotica" -> com.example.domain.models.ContentRating.ADULT_18_PLUS
                                        "suggestive" -> com.example.domain.models.ContentRating.MATURE
                                        else -> com.example.domain.models.ContentRating.GENERAL
                                    }
                                }
                                "lastChapter" -> {
                                    val ch = reader.nextStringOrEmpty()
                                    if (ch.isNotBlank()) lastChapter = "فصل $ch"
                                }
                                "tags" -> {
                                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                        reader.beginArray()
                                        while (reader.hasNext()) {
                                            parseTagName(reader)?.let { tag ->
                                                if (tags.size < 8 && !tags.contains(tag)) {
                                                    tags.add(tag)
                                                }
                                            }
                                        }
                                        reader.endArray()
                                    } else {
                                        reader.skipValue()
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                "relationships" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                var relType = ""
                                var fileName: String? = null
                                var personName: String? = null
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "type" -> relType = reader.nextStringOrEmpty()
                                        "attributes" -> {
                                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                                reader.beginObject()
                                                while (reader.hasNext()) {
                                                    when (reader.nextName()) {
                                                        "fileName" -> fileName = reader.nextStringOrEmpty()
                                                        "name" -> personName = reader.nextStringOrEmpty()
                                                        else -> reader.skipValue()
                                                    }
                                                }
                                                reader.endObject()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                when (relType) {
                                    "cover_art" -> if (!fileName.isNullOrBlank()) coverFileName = fileName
                                    "author" -> if (!personName.isNullOrBlank()) authorName = personName
                                    "artist" -> if (!personName.isNullOrBlank()) artistName = personName
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endArray()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (mdId.isBlank()) return null
        val finalTitle = primaryTitle.ifBlank { altTitles.firstOrNull().orEmpty() }.trim()
        if (finalTitle.isBlank()) return null

        val coverUrl = if (!coverFileName.isNullOrBlank()) {
            "https://uploads.mangadex.org/covers/$mdId/$coverFileName.512.jpg"
        } else {
            null
        }

        val summary = MangaSummary(
            id = "${sourceId}_$mdId",
            sourceId = sourceId,
            title = finalTitle,
            coverUrl = coverUrl,
            mangaUrl = "https://mangadex.org/title/$mdId",
            latestChapter = lastChapter,
            genres = tags,
            rating = null,
            status = status,
            contentRating = ratingClassification
        )
        val extra = ParsedExtraMetadata(
            description = description,
            altTitles = altTitles,
            author = authorName,
            artist = artistName
        )
        return summary to extra
    }

    fun parseChaptersFeedResponse(
        jsonString: String,
        mangaId: String,
        sourceId: String = "mangadex"
    ): List<MangaChapter> {
        if (jsonString.isBlank()) return emptyList()
        val chapters = mutableListOf<MangaChapter>()

        Buffer().writeUtf8(jsonString).use { buffer ->
            JsonReader.of(buffer).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "data" -> {
                            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                reader.beginArray()
                                var idx = 0
                                while (reader.hasNext()) {
                                    idx++
                                    parseSingleChapterObject(reader, mangaId, sourceId, idx)?.let {
                                        chapters.add(it)
                                    }
                                }
                                reader.endArray()
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        return chapters
            .distinctBy { it.chapterNumber ?: it.id }
            .sortedBy { it.chapterNumber ?: 0f }
    }

    private fun parseSingleChapterObject(
        reader: JsonReader,
        mangaId: String,
        sourceId: String,
        fallbackIndex: Int
    ): MangaChapter? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }

        var chapterUuid = ""
        var chapterNumStr = ""
        var chapterTitle = ""
        var lang = ""
        var publishAt = ""
        var pagesCount = 1

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> chapterUuid = reader.nextStringOrEmpty()
                "attributes" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "chapter" -> chapterNumStr = reader.nextStringOrEmpty()
                                "title" -> chapterTitle = reader.nextStringOrEmpty()
                                "translatedLanguage" -> lang = reader.nextStringOrEmpty()
                                "publishAt" -> publishAt = reader.nextStringOrEmpty()
                                "pages" -> {
                                    pagesCount = if (reader.peek() == JsonReader.Token.NUMBER) {
                                        reader.nextInt()
                                    } else {
                                        reader.skipValue()
                                        1
                                    }
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (chapterUuid.isBlank()) return null
        val numFloat = chapterNumStr.toFloatOrNull() ?: fallbackIndex.toFloat()
        val displayNum = chapterNumStr.ifBlank { fallbackIndex.toString() }
        val langBadge = if (lang.isNotBlank()) " [${lang.uppercase(Locale.ROOT)}]" else ""
        val displayTitle = if (chapterTitle.isNotBlank()) {
            "$displayNum - $chapterTitle$langBadge"
        } else {
            "$displayNum$langBadge"
        }
        val dateShort = publishAt.take(10).ifBlank { "متاح" }

        return MangaChapter(
            id = "${mangaId}_ch_$chapterUuid",
            mangaId = mangaId,
            sourceId = sourceId,
            remoteId = "https://api.mangadex.org/at-home/server/$chapterUuid",
            title = displayTitle,
            chapterNumber = numFloat,
            uploadDate = System.currentTimeMillis() - fallbackIndex * 3_600_000L,
            dateLabel = dateShort
        )
    }

    fun parseAtHomePagesResponse(jsonString: String): List<MangaPage> {
        if (jsonString.isBlank()) return emptyList()
        var baseUrl = "https://uploads.mangadex.org"
        var hash = ""
        val dataSaverFiles = mutableListOf<String>()
        val dataFiles = mutableListOf<String>()

        Buffer().writeUtf8(jsonString).use { buffer ->
            JsonReader.of(buffer).use { reader ->
                if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) return emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "baseUrl" -> baseUrl = reader.nextStringOrEmpty().ifBlank { "https://uploads.mangadex.org" }
                        "chapter" -> {
                            if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "hash" -> hash = reader.nextStringOrEmpty()
                                        "dataSaver" -> {
                                            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    val f = reader.nextStringOrEmpty()
                                                    if (f.isNotBlank()) dataSaverFiles.add(f)
                                                }
                                                reader.endArray()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        "data" -> {
                                            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                                                reader.beginArray()
                                                while (reader.hasNext()) {
                                                    val f = reader.nextStringOrEmpty()
                                                    if (f.isNotBlank()) dataFiles.add(f)
                                                }
                                                reader.endArray()
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            } else {
                                reader.skipValue()
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }

        if (hash.isBlank()) return emptyList()
        val chosen = if (dataSaverFiles.isNotEmpty()) {
            dataSaverFiles.map { "$baseUrl/data-saver/$hash/$it" }
        } else {
            dataFiles.map { "$baseUrl/data/$hash/$it" }
        }

        return chosen.mapIndexed { idx, url ->
            MangaPage(index = idx, imageUrl = url)
        }
    }

    private fun parseTagName(reader: JsonReader): String? {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var tagName: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "attributes" -> {
                    if (reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "name" -> {
                                    val map = readLocalizedStringMap(reader)
                                    val raw = map["ar"] ?: map["en"] ?: map.values.firstOrNull()
                                    tagName = translateGenreToArabic(raw)
                                }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return tagName
    }

    private fun translateGenreToArabic(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        return when (tag.trim().lowercase(Locale.ROOT)) {
            "action" -> "اكشن"
            "fantasy" -> "فانتازيا"
            "isekai" -> "إيسيكاي"
            "romance" -> "رومانسي"
            "drama" -> "دراما"
            "adventure" -> "مغامرة"
            "comedy" -> "كوميدي"
            "martial arts" -> "فنون قتالية"
            "reincarnation", "time travel" -> "إيسيكاي"
            "magic" -> "سحر"
            "mystery" -> "غموض"
            "thriller", "psychological" -> "إثارة"
            "historical" -> "تاريخي"
            "school life" -> "الحياة المدرسية"
            "sci-fi" -> "خيال علمي"
            "web comic", "long strip" -> "مانهوا"
            else -> tag
        }
    }

    private fun readLocalizedStringMap(reader: JsonReader): Map<String, String> {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            reader.skipValue()
            return emptyMap()
        }
        val map = LinkedHashMap<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val k = reader.nextName()
            val v = reader.nextStringOrEmpty()
            if (v.isNotBlank()) {
                map[k] = v
            }
        }
        reader.endObject()
        return map
    }

    private fun JsonReader.nextStringOrEmpty(): String {
        return when (peek()) {
            JsonReader.Token.STRING -> nextString()
            JsonReader.Token.NUMBER -> nextString()
            JsonReader.Token.BOOLEAN -> nextBoolean().toString()
            JsonReader.Token.NULL -> {
                nextNull<Unit>()
                ""
            }
            else -> {
                skipValue()
                ""
            }
        }
    }
}
