package com.patreonkt.server

import android.content.SharedPreferences
import com.google.gson.Gson
import com.patreonkt.db.PatreonDatabase
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream

private const val MIME_JSON = "application/json"

/**
 * Embedded HTTP server exposing a REST API over localhost for browsing archived Patreon content.
 *
 * Start with [start] and stop with [stop]. The port is supplied at construction time.
 * All endpoints return `application/json` with a permissive CORS header.
 *
 * ### Endpoints
 *
 * | Method | Path | Description |
 * |--------|------|-------------|
 * | GET | `/api/campaigns` | All campaigns |
 * | GET | `/api/campaigns/{id}` | Single campaign |
 * | GET | `/api/campaigns/{id}/posts` | Posts for a campaign (`?page=&limit=`) |
 * | GET | `/api/posts/{id}` | Single post |
 * | GET | `/api/posts/{id}/media` | Media for a post |
 * | GET | `/api/search?q=` | FTS4 full-text search across posts |
 * | GET | `/api/settings` | Read persisted settings |
 * | PUT | `/api/settings` | Write settings (JSON body) |
 * | GET | `/api/media/stream/{id}` | Stream a local file by media ID |
 *
 * @param port TCP port to listen on. Pass `0` to let the OS assign a free port.
 * @param db Room database instance.
 * @param prefs SharedPreferences for settings persistence.
 */
class BrowseServer(
    port: Int,
    private val db: PatreonDatabase,
    private val prefs: SharedPreferences
) : NanoHTTPD(port) {

    private val gson = Gson()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val method = session.method

        return try {
            route(uri, method, session)
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, mapOf("error" to e.message))
        }
    }

    private fun route(uri: String, method: Method, session: IHTTPSession): Response {
        // GET /api/campaigns
        if (uri == "/api/campaigns" && method == Method.GET)
            return handleGetCampaigns()

        // GET /api/campaigns/{id}
        val campaignById = Regex("^/api/campaigns/([^/]+)$").matchEntire(uri)
        if (campaignById != null && method == Method.GET)
            return handleGetCampaign(campaignById.groupValues[1])

        // GET /api/campaigns/{id}/posts
        val campaignPosts = Regex("^/api/campaigns/([^/]+)/posts$").matchEntire(uri)
        if (campaignPosts != null && method == Method.GET)
            return handleGetCampaignPosts(campaignPosts.groupValues[1], session.parameters)

        // GET /api/posts/{id}
        val postById = Regex("^/api/posts/([^/]+)$").matchEntire(uri)
        if (postById != null && method == Method.GET)
            return handleGetPost(postById.groupValues[1])

        // GET /api/posts/{id}/media
        val postMedia = Regex("^/api/posts/([^/]+)/media$").matchEntire(uri)
        if (postMedia != null && method == Method.GET)
            return handleGetPostMedia(postMedia.groupValues[1])

        // GET /api/search?q=
        if (uri == "/api/search" && method == Method.GET)
            return handleSearch(session.parameters["q"]?.firstOrNull() ?: "")

        // GET /api/settings
        if (uri == "/api/settings" && method == Method.GET)
            return handleGetSettings()

        // PUT /api/settings
        if (uri == "/api/settings" && method == Method.PUT)
            return handlePutSettings(session)

        // GET /api/media/stream/{id}
        val mediaStream = Regex("^/api/media/stream/(.+)$").matchEntire(uri)
        if (mediaStream != null && method == Method.GET)
            return handleStreamMedia(mediaStream.groupValues[1], session)

        return jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Not found: $uri"))
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private fun handleGetCampaigns(): Response = runBlocking {
        val campaigns = db.campaignDao().getAllOnce()
        jsonResponse(Response.Status.OK, campaigns)
    }

    private fun handleGetCampaign(id: String): Response = runBlocking {
        val campaign = db.campaignDao().getById(id)
            ?: return@runBlocking jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Campaign $id not found"))
        jsonResponse(Response.Status.OK, campaign)
    }

    private fun handleGetCampaignPosts(campaignId: String, params: Map<String, List<String>>): Response = runBlocking {
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 20
        val page = params["page"]?.firstOrNull()?.toIntOrNull() ?: 0
        val posts = db.postDao().getPostsByCampaignOnce(campaignId)
        val paged = posts.drop(page * limit).take(limit)
        jsonResponse(Response.Status.OK, mapOf(
            "posts" to paged,
            "total" to posts.size,
            "page" to page,
            "limit" to limit
        ))
    }

    private fun handleGetPost(id: String): Response = runBlocking {
        val post = db.postDao().getById(id)
            ?: return@runBlocking jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Post $id not found"))
        jsonResponse(Response.Status.OK, post)
    }

    private fun handleGetPostMedia(postId: String): Response = runBlocking {
        val media = db.mediaDao().getMediaForPost(postId)
        jsonResponse(Response.Status.OK, media)
    }

    private fun handleSearch(query: String): Response = runBlocking {
        if (query.isBlank()) return@runBlocking jsonResponse(Response.Status.BAD_REQUEST, mapOf("error" to "q is required"))
        val results = db.postDao().search(query)
        jsonResponse(Response.Status.OK, mapOf("query" to query, "results" to results))
    }

    private fun handleGetSettings(): Response {
        val all = prefs.all.mapValues { it.value.toString() }
        return jsonResponse(Response.Status.OK, all)
    }

    private fun handlePutSettings(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        session.inputStream.read(body)
        val json = String(body)
        @Suppress("UNCHECKED_CAST")
        val map = runCatching { gson.fromJson(json, Map::class.java) as Map<String, Any> }.getOrElse { emptyMap() }
        val editor = prefs.edit()
        map.forEach { (k, v) -> editor.putString(k, v.toString()) }
        editor.apply()
        return jsonResponse(Response.Status.OK, mapOf("saved" to map.size))
    }

    private fun handleStreamMedia(mediaId: String, session: IHTTPSession): Response = runBlocking {
        val media = db.mediaDao().getById(mediaId)
            ?: return@runBlocking jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Media $mediaId not found"))
        val path = media.localPath
            ?: return@runBlocking jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "Media $mediaId not downloaded yet"))
        val file = java.io.File(path)
        if (!file.exists()) return@runBlocking jsonResponse(Response.Status.NOT_FOUND, mapOf("error" to "File missing: $path"))

        val mimeType = media.mimeType ?: "application/octet-stream"
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeParts = rangeHeader.removePrefix("bytes=").split("-")
            val start = rangeParts[0].toLongOrNull() ?: 0L
            val end = rangeParts.getOrNull(1)?.toLongOrNull() ?: (file.length() - 1)
            val length = end - start + 1
            val stream = FileInputStream(file).also { it.skip(start) }
            val response = newFixedLengthResponse(
                Response.Status.PARTIAL_CONTENT, mimeType, stream, length
            )
            response.addHeader("Content-Range", "bytes $start-$end/${file.length()}")
            response.addHeader("Accept-Ranges", "bytes")
            response
        } else {
            val response = newFixedLengthResponse(
                Response.Status.OK, mimeType, FileInputStream(file), file.length()
            )
            response.addHeader("Accept-Ranges", "bytes")
            response
        }
    }

    // ── Utility ────────────────────────────────────────────────────────────

    private fun jsonResponse(status: Response.Status, body: Any): Response {
        val response = newFixedLengthResponse(status, MIME_JSON, gson.toJson(body))
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, PUT, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        return response
    }
}
