package com.patreonkt.mock

import android.content.Context
import com.google.gson.Gson
import com.patreonkt.api.*
import com.patreonkt.R

/**
 * In-memory [PatreonApi] implementation that serves synthetic campaign and post data.
 *
 * Use this backend to exercise the full download-and-indexing pipeline without any network access
 * or Patreon credentials. It is the default when [com.patreonkt.PatreonArchiver.Builder.mockBackend]
 * is called.
 *
 * Mock data is loaded from `res/raw/mock_*.json` assets bundled with the library. The campaign
 * has:
 * - 1 campaign (`mock-creator` / "Mock Campaign")
 * - 3 posts: one image post (2 images), one audio post, one post with a YouTube embed
 * - 1 shop product
 *
 * @param context Application or test context used to open raw resource streams.
 */
class MockPatreonBackend(private val context: Context) : PatreonApi {

    private val gson = Gson()

    override suspend fun resolveCampaignId(url: String): Result<String> =
        Result.success("12345678")

    override suspend fun fetchCampaign(campaignId: String): Result<CampaignResponse> =
        runCatching {
            val raw = readRaw(R.raw.mock_campaign)
            parseCampaignResponse(raw)
        }

    override suspend fun fetchPostsPage(
        campaignId: String,
        cursor: String?
    ): Result<PostsPageResponse> = runCatching {
        // Return page 1 on first call (cursor == null), empty page 2 otherwise
        val raw = if (cursor == null) readRaw(R.raw.mock_posts_page1)
                  else readRaw(R.raw.mock_posts_page2)
        parsePostsPageResponse(raw)
    }

    override suspend fun fetchProducts(campaignId: String): Result<ProductsResponse> =
        runCatching {
            val raw = readRaw(R.raw.mock_products)
            parseProductsResponse(raw)
        }

    override suspend fun fetchComments(postId: String): Result<CommentsResponse> =
        Result.success(CommentsResponse(emptyList(), null))

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun readRaw(resId: Int): String =
        context.resources.openRawResource(resId).bufferedReader().readText()

    @Suppress("UNCHECKED_CAST")
    private fun parseCampaignResponse(raw: String): CampaignResponse {
        val root = gson.fromJson(raw, Map::class.java) as Map<String, Any?>
        val data = root["data"] as Map<String, Any?>
        val included = buildIncludedMap(root["included"] as? List<*>)
        val campaign = parseResource(data)!!
        val creatorRelId = extractRelId(data, "creator")
        val creator = creatorRelId?.let { included["user" to it] }
        return CampaignResponse(campaign, creator, raw)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePostsPageResponse(raw: String): PostsPageResponse {
        val root = gson.fromJson(raw, Map::class.java) as Map<String, Any?>
        val dataList = root["data"] as? List<*> ?: emptyList<Any>()
        val included = buildIncludedMap(root["included"] as? List<*>)
        val posts = dataList.mapNotNull { parseResource(it as? Map<String, Any?> ?: return@mapNotNull null) }
        val cursor = ((root["meta"] as? Map<*, *>)?.get("pagination") as? Map<*, *>)
            ?.get("cursors")?.let { (it as? Map<*, *>)?.get("next")?.toString() }
        return PostsPageResponse(posts, included, cursor)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseProductsResponse(raw: String): ProductsResponse {
        val root = gson.fromJson(raw, Map::class.java) as Map<String, Any?>
        val dataList = root["data"] as? List<*> ?: emptyList<Any>()
        val included = buildIncludedMap(root["included"] as? List<*>)
        val products = dataList.mapNotNull { parseResource(it as? Map<String, Any?> ?: return@mapNotNull null) }
        return ProductsResponse(products, included)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseResource(map: Map<String, Any?>?): JsonApiResource? {
        val type = map?.get("type")?.toString() ?: return null
        val id = map["id"]?.toString() ?: return null
        val attrs = map["attributes"] as? Map<String, Any?> ?: emptyMap()
        val rels = map["relationships"] as? Map<String, Any?> ?: emptyMap()
        return JsonApiResource(type, id, attrs, rels)
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildIncludedMap(list: List<*>?): IncludedMap {
        if (list == null) return emptyMap()
        return list.mapNotNull { item ->
            val map = item as? Map<String, Any?> ?: return@mapNotNull null
            val res = parseResource(map) ?: return@mapNotNull null
            (res.type to res.id) to res
        }.toMap()
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRelId(data: Map<String, Any?>, relName: String): String? {
        val rels = data["relationships"] as? Map<*, *> ?: return null
        val rel = rels[relName] as? Map<*, *> ?: return null
        val relData = rel["data"] as? Map<*, *> ?: return null
        return relData["id"]?.toString()
    }
}
