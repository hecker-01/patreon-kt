package com.patreonkt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.patreonkt.auth.PatreonCookieJar
import com.patreonkt.db.PatreonDatabase
import com.patreonkt.db.entities.CampaignEntity
import com.patreonkt.download.DownloadManager
import com.patreonkt.download.HlsDownloader
import com.patreonkt.download.MediaDownloader
import com.patreonkt.mock.MockPatreonBackend
import com.patreonkt.storage.CampaignFileSystem
import com.patreonkt.storage.SidecarWriter
import com.patreonkt.youtube.YoutubeDownloader
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * End-to-end integration test using [MockPatreonBackend].
 *
 * Verifies the complete pipeline: API → DB → filesystem → sidecar JSON.
 * No network access is required.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MockArchiveTest {

    private lateinit var context: Context
    private lateinit var db: PatreonDatabase
    private lateinit var outputDir: File
    private lateinit var config: ArchiverConfig
    private lateinit var downloadManager: DownloadManager

    private val dbWriteEntities = mutableListOf<String>()
    private val downloadStarts = mutableListOf<String>()
    private val downloadCompletes = mutableListOf<String>()
    private val eventScope = CoroutineScope(Dispatchers.IO)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        outputDir = File(context.cacheDir, "patreon-test-${System.currentTimeMillis()}")
        outputDir.mkdirs()

        PatreonDatabase.clearInstance()
        db = PatreonDatabase.getInstance(context, ":memory:")

        config = ArchiverConfig(
            outputDir = outputDir,
            dryRun = false,
            downloadThumbnails = false,
            maxParallelJobs = 1,
            requestSpacingMs = 0L,
            maxRetries = 1
        )

        val api = MockPatreonBackend(context)
        val cookieJar = PatreonCookieJar()
        val httpClient = OkHttpClient()
        val mediaDownloader = MediaDownloader(httpClient, config)
        val hlsDownloader = HlsDownloader(config, mediaDownloader)
        val youtubeDownloader = YoutubeDownloader(config)
        val fileSystem = CampaignFileSystem(config)
        val sidecarWriter = SidecarWriter()

        downloadManager = DownloadManager(
            api, config, db, fileSystem, sidecarWriter,
            mediaDownloader, hlsDownloader, youtubeDownloader, cookieJar
        )

        eventScope.launch {
            downloadManager.events.collect { event ->
                when (event) {
                    is DownloadEvent.Start   -> downloadStarts.add(event.item.id)
                    is DownloadEvent.Complete -> downloadCompletes.add(event.item.id)
                    is DownloadEvent.DbWrite  -> dbWriteEntities.add(event.entity::class.simpleName ?: "?")
                    else -> {}
                }
            }
        }
    }

    @After
    fun tearDown() {
        db.close()
        PatreonDatabase.clearInstance()
        outputDir.deleteRecursively()
    }

    @Test
    fun `downloadCampaign stores campaign in DB`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val campaigns = db.campaignDao().getAllOnce()
        assertEquals("Expected exactly 1 campaign", 1, campaigns.size)
        assertEquals("12345678", campaigns[0].id)
        assertEquals("Mock Campaign", campaigns[0].name)
        assertEquals("mock-creator", campaigns[0].vanity)
    }

    @Test
    fun `downloadCampaign stores 3 posts in DB`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val postCount = db.postDao().count()
        assertEquals("Expected exactly 3 posts", 3, postCount)

        val post = db.postDao().getById("111111")
        assertNotNull("Post 111111 should exist", post)
        assertEquals("First Image Post", post!!.title)
    }

    @Test
    fun `downloadCampaign creates campaign_info sidecar`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val campaignDir = File(outputDir, "mock-creator - Mock Campaign")
        assertTrue("Campaign directory should exist", campaignDir.exists())

        val sidecar = File(campaignDir, "campaign_info/campaign_info.json")
        assertTrue("campaign_info.json should exist", sidecar.exists())

        val json = JsonParser.parseString(sidecar.readText())
        assertTrue("Sidecar must be valid JSON object", json.isJsonObject)
        assertEquals("12345678", json.asJsonObject.get("id").asString)
    }

    @Test
    fun `downloadCampaign creates post_info sidecars for each post`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val campaignDir = File(outputDir, "mock-creator - Mock Campaign")
        val postsDir = File(campaignDir, "posts")
        assertTrue("posts/ directory should exist", postsDir.exists())

        val postDirs = postsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals("Expected 3 post directories", 3, postDirs.size)

        for (postDir in postDirs) {
            val sidecar = File(postDir, "post_info/post_info.json")
            assertTrue("post_info.json missing in ${postDir.name}", sidecar.exists())
            val json = JsonParser.parseString(sidecar.readText())
            assertTrue(json.isJsonObject)
        }
    }

    @Test
    fun `downloadCampaign creates shop product directory`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val campaignDir = File(outputDir, "mock-creator - Mock Campaign")
        val shopDir = File(campaignDir, "shop")
        assertTrue("shop/ directory should exist", shopDir.exists())

        val productDirs = shopDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        assertEquals("Expected 1 product directory", 1, productDirs.size)
        assertTrue("Product dir should start with p001", productDirs[0].name.startsWith("p001"))

        val sidecar = File(productDirs[0], "product_info/product_info.json")
        assertTrue("product_info.json should exist", sidecar.exists())
    }

    @Test
    fun `downloadCampaign stores product in DB`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val products = db.productDao().getProductsByCampaign("12345678")
        assertEquals("Expected 1 product", 1, products.size)
        assertEquals("p001", products[0].id)
        assertEquals("Mock Digital Print", products[0].name)
    }

    @Test
    fun `media entities are stored in DB for image post`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val media = db.mediaDao().getMediaForPost("111111")
        assertTrue("Image post should have at least 1 media entry", media.isNotEmpty())
        assertTrue("First media should be IMAGE type", media.any { it.mediaType == "IMAGE" })
    }

    @Test
    fun `campaign directory name matches patreon-dl convention`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val expectedDir = File(outputDir, "mock-creator - Mock Campaign")
        assertTrue("Directory must follow {vanity} - {name} convention", expectedDir.exists())
    }

    @Test
    fun `post directories start with post ID`() = runTest {
        downloadManager.downloadCampaign("https://www.patreon.com/mock-creator")

        val postsDir = File(outputDir, "mock-creator - Mock Campaign/posts")
        val postDirs = postsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        val ids = listOf("111111", "222222", "333333")
        for (id in ids) {
            assertTrue("Post dir for $id should exist", postDirs.any { it.name.startsWith(id) })
        }
    }
}
