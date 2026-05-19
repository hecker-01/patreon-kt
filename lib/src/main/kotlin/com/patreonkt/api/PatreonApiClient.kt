package com.patreonkt.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.patreonkt.ArchiverConfig
import com.patreonkt.auth.PatreonCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy

private const val BASE = "https://www.patreon.com"
private const val API = "$BASE/api"

/**
 * Live implementation of [PatreonApi] backed by OkHttp.
 *
 * Configured from [ArchiverConfig]: proxy, user-agent, cookie file, and request spacing are all
 * applied here. All network calls run on [Dispatchers.IO] and return [Result]-wrapped values so
 * callers never need to catch exceptions.
 *
 * @param config Full archiver configuration.
 * @param cookieJar The cookie jar to attach to every request.
 */
class PatreonApiClient(
    private val config: ArchiverConfig,
    private val cookieJar: PatreonCookieJar
) : PatreonApi {

    private val gson = Gson()
    private val client: OkHttpClient = buildClient()

    // ── PatreonApi ──────────────────────────────────────────────────────────

    override suspend fun resolveCampaignId(url: String): Result<String> = runCatching {
        withContext(Dispatchers.IO) {
            val html = get(url)
            extractCampaignId(html)
                ?: throw IllegalStateException("Could not find campaignId in page HTML for $url")
        }
    }

    override suspend fun fetchCampaign(campaignId: String): Result<CampaignResponse> = runCatching {
        withContext(Dispatchers.IO) {
            val url = "$API/campaigns/$campaignId" +
                "?include=creator%2Crewards%2Cgoals%2Cpostings" +
                "&fields[campaign]=name,vanity,summary,creation_name,patron_count," +
                "published_at,created_at,url,avatar_photo_url,cover_photo_url,currency" +
                "&fields[user]=full_name,vanity,image_url"
            val raw = get(url)
            parseCampaignResponse(raw)
        }
    }

    override suspend fun fetchPostsPage(
        campaignId: String,
        cursor: String?
    ): Result<PostsPageResponse> = runCatching {
        withContext(Dispatchers.IO) {
            val cursorParam = cursor?.let { "&page[cursor]=$it" } ?: ""
            val url = "$API/posts" +
                "?filter[campaign_id]=$campaignId" +
                "&include=user%2Cattachments%2Cmedia%2Cembed%2Cpoll.choices%2Cpoll.current_user_responses.user%2Cpoll.current_user_responses.choice" +
                "&fields[post]=title,content,teaser_text,post_type,is_viewable,url,published_at," +
                "edited_at,comment_count,current_user_can_view,embed,image,thumbnail_url" +
                "&fields[media]=id,image_urls,download_url,metadata,file_name,mimetype" +
                "&fields[attachment]=url,name,mimetype" +
                "&sort=-published_at&page[count]=12$cursorParam"
            val raw = get(url)
            parsePostsPageResponse(raw)
        }
    }

    override suspend fun fetchProducts(campaignId: String): Result<ProductsResponse> = runCatching {
        withContext(Dispatchers.IO) {
            val url = "$API/campaigns/$campaignId/products" +
                "?include=media&fields[product]=name,description,url,price_cents,currency,published_at"
            val raw = get(url)
            parseProductsResponse(raw)
        }
    }

    override suspend fun fetchComments(postId: String): Result<CommentsResponse> = runCatching {
        withContext(Dispatchers.IO) {
            val url = "$API/posts/$postId/comments" +
                "?include=commenter&fields[comment]=body,created,is_by_patron,is_by_creator" +
                "&page[count]=50"
            val raw = get(url)
            parseCommentsResponse(raw)
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Referer", BASE)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            response.body?.string() ?: throw IllegalStateException("Empty body for $url")
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private fun extractCampaignId(html: String): String? {
        // Strategy 1: __NEXT_DATA__ bootstrap JSON
        val nextDataRegex = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        nextDataRegex.find(html)?.groupValues?.get(1)?.let { json ->
            runCatching {
                val root = JSONObject(json)
                root.getJSONObject("props")
                    .getJSONObject("pageProps")
                    .getJSONObject("bootstrapEnvelope")
                    .getJSONObject("bootstrap")
                    .getJSONObject("campaign")
                    .getJSONObject("data")
                    .getString("id")
            }.getOrNull()?.let { return it }
        }
        // Strategy 2: window.patreon.bootstrap.campaign.data.id
        val legacyRegex = Regex(""""campaign":\s*\{"data":\s*\{"type":\s*"campaign",\s*"id":\s*"(\d+)"""")
        legacyRegex.find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseCampaignResponse(raw: String): CampaignResponse {
        val root = gson.fromJson(raw, Map::class.java) as Map<String, Any?>
        val data = (root["data"] as Map<String, Any?>)
        val included = buildIncludedMap(root["included"] as? List<*>)
        val campaign = parseResource(data)
            ?: throw IllegalStateException("Could not parse campaign resource from response")
        val creatorId = extractRelId(data, "creator")
        val creator = creatorId?.let { included["user" to it] }
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
    private fun parseCommentsResponse(raw: String): CommentsResponse {
        val root = gson.fromJson(raw, Map::class.java) as Map<String, Any?>
        val dataList = root["data"] as? List<*> ?: emptyList<Any>()
        val comments = dataList.mapNotNull { parseResource(it as? Map<String, Any?> ?: return@mapNotNull null) }
        val cursor = ((root["meta"] as? Map<*, *>)?.get("pagination") as? Map<*, *>)
            ?.get("cursors")?.let { (it as? Map<*, *>)?.get("next")?.toString() }
        return CommentsResponse(comments, cursor)
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

    // ── OkHttp client construction ──────────────────────────────────────────

    private fun buildClient(): OkHttpClient {
        val proxy = config.proxy?.let {
            val socketType = when (it.type) {
                com.patreonkt.ProxyType.SOCKS -> Proxy.Type.SOCKS
                else -> Proxy.Type.HTTP
            }
            Proxy(socketType, InetSocketAddress(it.host, it.port))
        }
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", config.userAgent)
                        .build()
                )
            }

        proxy?.let { p ->
            builder.proxy(p)
            config.proxy?.takeIf { it.username != null }?.let { pc ->
                builder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(pc.username!!, pc.password ?: "")
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
            }
        }

        if (android.util.Log.isLoggable("OkHttp", android.util.Log.DEBUG)) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}

/** Thrown when the Patreon API returns a non-2xx response. */
class HttpException(val code: Int, val url: String) : Exception("HTTP $code for $url")
