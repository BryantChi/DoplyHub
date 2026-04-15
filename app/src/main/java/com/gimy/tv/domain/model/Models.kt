package com.gimy.tv.domain.model

enum class SourceType { GIMYMAX, GIMYTV, MOVIEFFM }

val SourceType.displayName: String get() = when (this) {
    SourceType.GIMYMAX -> "GimyMax"
    SourceType.GIMYTV -> "GimyTV"
    SourceType.MOVIEFFM -> "MovieFFM"
}

data class Category(
    val id: Int,
    val name: String,
    val sourceType: SourceType
)

data class Vod(
    val id: Long,
    val sourceType: SourceType,
    val title: String,
    val coverUrl: String,
    val category: String,
    val year: Int,
    val status: String,
    val rating: Double? = null
)

data class VodDetail(
    val vod: Vod,
    val director: String,
    val actors: List<String>,
    val synopsis: String,
    val episodes: List<EpisodeGroup>,
    val seriesVods: List<Vod> = emptyList(),
    val relatedVods: List<Vod> = emptyList()
)

data class EpisodeGroup(
    val sourceName: String,
    val sourceId: Int,
    val episodes: List<Episode>
)

data class Episode(
    val number: Int,
    val title: String,
    val playUrl: String
)

data class PlayerData(
    val streamUrl: String,
    val encrypt: Int,
    val from: String
)

data class PaginatedResult<T>(
    val items: List<T>,
    val currentPage: Int,
    val totalPages: Int,
    val hasMore: Boolean
)
