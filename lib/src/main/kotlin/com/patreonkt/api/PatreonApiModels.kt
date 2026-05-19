package com.patreonkt.api

import com.google.gson.JsonObject

/**
 * A single JSON-API resource object from a Patreon response.
 *
 * @property type JSON-API resource type (e.g. `"campaign"`, `"post"`, `"media"`).
 * @property id Patreon resource identifier.
 * @property attributes Flat map of attribute key → raw value.
 * @property relationships Map of relationship name → relationship data.
 */
data class JsonApiResource(
    val type: String,
    val id: String,
    val attributes: Map<String, Any?> = emptyMap(),
    val relationships: Map<String, Any?> = emptyMap()
) {
    /** Convenience accessor for string attributes. */
    fun str(key: String): String? = attributes[key]?.toString()

    /** Convenience accessor for int attributes. */
    fun int(key: String): Int? = attributes[key]?.toString()?.toIntOrNull()

    /** Convenience accessor for boolean attributes. */
    fun bool(key: String): Boolean? = attributes[key]?.toString()?.toBoolean()
}

/** Included-resource registry: (type, id) → resource. */
typealias IncludedMap = Map<Pair<String, String>, JsonApiResource>

/**
 * Parsed campaign + creator from a `/api/campaigns/{id}` response.
 */
data class CampaignResponse(
    val campaign: JsonApiResource,
    val creator: JsonApiResource?,
    val rawJson: String
)

/**
 * A single page of posts from `/api/posts`.
 *
 * @property posts Post resources for this page.
 * @property included Flat included-resource registry for resolving relationships.
 * @property nextCursor Opaque cursor for the next page; null when there are no more pages.
 */
data class PostsPageResponse(
    val posts: List<JsonApiResource>,
    val included: IncludedMap,
    val nextCursor: String?
)

/**
 * All shop products from `/api/campaigns/{id}/products`.
 */
data class ProductsResponse(
    val products: List<JsonApiResource>,
    val included: IncludedMap
)

/**
 * Comments for a single post from `/api/posts/{id}/comments`.
 */
data class CommentsResponse(
    val comments: List<JsonApiResource>,
    val nextCursor: String?
)

/** Helper to extract the media `data` array from a post's `media` relationship. */
@Suppress("UNCHECKED_CAST")
internal fun JsonApiResource.mediaIds(): List<Pair<String, String>> {
    val rel = relationships["media"] as? Map<*, *> ?: return emptyList()
    val data = rel["data"] as? List<*> ?: return emptyList()
    return data.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val type = map["type"]?.toString() ?: return@mapNotNull null
        val id = map["id"]?.toString() ?: return@mapNotNull null
        type to id
    }
}

/** Extracts the `embed` object from a post's attributes if present. */
@Suppress("UNCHECKED_CAST")
internal fun JsonApiResource.embedData(): Map<String, String?>? {
    val embed = attributes["embed"] as? Map<*, *> ?: return null
    return mapOf(
        "url" to embed["url"]?.toString(),
        "subject" to embed["subject"]?.toString(),
        "html" to embed["html"]?.toString()
    )
}
