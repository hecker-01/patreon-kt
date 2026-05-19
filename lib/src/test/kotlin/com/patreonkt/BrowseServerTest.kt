package com.patreonkt

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.patreonkt.db.PatreonDatabase
import com.patreonkt.db.entities.CampaignEntity
import com.patreonkt.db.entities.PostEntity
import com.patreonkt.server.BrowseServer
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the embedded [BrowseServer] REST API.
 *
 * Starts a server on an OS-assigned port (port=0), makes real HTTP requests,
 * and asserts correct JSON responses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BrowseServerTest {

    private lateinit var context: Context
    private lateinit var db: PatreonDatabase
    private lateinit var prefs: SharedPreferences
    private lateinit var server: BrowseServer
    private lateinit var client: OkHttpClient
    private var baseUrl: String = ""

    @Before
    fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        PatreonDatabase.clearInstance()
        db = PatreonDatabase.getInstance(context, ":memory:")
        prefs = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        // Seed some data
        db.campaignDao().insert(
            CampaignEntity(
                id = "camp1", name = "Mock Campaign", vanity = "mock-creator",
                summary = "A test campaign", url = "https://www.patreon.com/mock-creator",
                currency = "USD", createdAt = null, publishedAt = null, patronCount = 10,
                avatarImageUrl = null, coverPhotoUrl = null,
                creatorId = "u1", creatorName = "Mock Creator", rawJson = "{}"
            )
        )
        db.postDao().insert(
            PostEntity(
                id = "p1", campaignId = "camp1", title = "Mock Post One",
                content = "Hello world", teaserText = null, postType = "text_only",
                isViewable = true, url = null, publishedAt = "2024-01-01T00:00:00Z",
                editedAt = null, commentCount = 0, coverImageUrl = null, thumbnailUrl = null,
                embedUrl = null, embedSubject = null, rawJson = "{}"
            )
        )

        // Start server on OS-assigned port
        server = BrowseServer(0, db, prefs)
        server.start(5000, false)
        baseUrl = "http://localhost:${server.listeningPort}"
        client = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.stop()
        db.close()
        PatreonDatabase.clearInstance()
    }

    @Test
    fun `GET campaigns returns JSON array`() {
        val response = get("/api/campaigns")
        assertEquals(200, response.code)
        val json = JsonParser.parseString(response.body?.string())
        assertTrue("Response should be JSON array", json.isJsonArray)
        assertEquals("Should have 1 campaign", 1, json.asJsonArray.size())
    }

    @Test
    fun `GET campaigns by id returns campaign object`() {
        val response = get("/api/campaigns/camp1")
        assertEquals(200, response.code)
        val json = JsonParser.parseString(response.body?.string()).asJsonObject
        assertEquals("camp1", json.get("id").asString)
        assertEquals("Mock Campaign", json.get("name").asString)
    }

    @Test
    fun `GET campaigns by unknown id returns 404`() {
        val response = get("/api/campaigns/nonexistent")
        assertEquals(404, response.code)
    }

    @Test
    fun `GET campaigns posts returns posts for campaign`() {
        val response = get("/api/campaigns/camp1/posts")
        assertEquals(200, response.code)
        val json = JsonParser.parseString(response.body?.string()).asJsonObject
        assertTrue("Response should have 'posts' array", json.has("posts"))
        assertEquals(1, json.getAsJsonArray("posts").size())
    }

    @Test
    fun `GET posts by id returns post object`() {
        val response = get("/api/posts/p1")
        assertEquals(200, response.code)
        val json = JsonParser.parseString(response.body?.string()).asJsonObject
        assertEquals("p1", json.get("id").asString)
        assertEquals("Mock Post One", json.get("title").asString)
    }

    @Test
    fun `GET search returns results for matching query`() {
        val response = get("/api/search?q=Mock")
        assertEquals(200, response.code)
        val json = JsonParser.parseString(response.body?.string()).asJsonObject
        assertTrue(json.has("results"))
        assertTrue(json.getAsJsonArray("results").size() >= 1)
    }

    @Test
    fun `GET search returns 400 for empty query`() {
        val response = get("/api/search?q=")
        assertEquals(400, response.code)
    }

    @Test
    fun `GET settings returns empty object initially`() {
        val response = get("/api/settings")
        assertEquals(200, response.code)
        val json = JsonParser.parseString(response.body?.string())
        assertTrue(json.isJsonObject)
    }

    @Test
    fun `PUT settings persists values and GET reads them back`() {
        val putBody = """{"theme":"dark","pageSize":"25"}""".toRequestBody("application/json".toMediaType())
        val putRequest = Request.Builder().url("$baseUrl/api/settings").put(putBody).build()
        val putResponse = client.newCall(putRequest).execute()
        assertEquals(200, putResponse.code)

        val getResponse = get("/api/settings")
        assertEquals(200, getResponse.code)
        val json = JsonParser.parseString(getResponse.body?.string()).asJsonObject
        assertEquals("dark", json.get("theme").asString)
        assertEquals("25", json.get("pageSize").asString)
    }

    @Test
    fun `unknown endpoint returns 404`() {
        val response = get("/api/nonexistent/path")
        assertEquals(404, response.code)
    }

    @Test
    fun `responses include CORS header`() {
        val response = get("/api/campaigns")
        assertEquals("*", response.header("Access-Control-Allow-Origin"))
    }

    private fun get(path: String): okhttp3.Response {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        return client.newCall(request).execute()
    }
}
