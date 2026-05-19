package com.patreonkt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.patreonkt.db.PatreonDatabase
import com.patreonkt.db.entities.PostEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the FTS4 full-text search index on [PostEntity].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FtsSearchTest {

    private lateinit var db: PatreonDatabase

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        PatreonDatabase.clearInstance()
        db = PatreonDatabase.getInstance(context, ":memory:")

        // Seed a campaign that the posts can reference
        runTest {
            db.campaignDao().insert(
                com.patreonkt.db.entities.CampaignEntity(
                    id = "camp1", name = "Test Campaign", vanity = "test",
                    summary = null, url = null, currency = null, createdAt = null,
                    publishedAt = null, patronCount = 0, avatarImageUrl = null,
                    coverPhotoUrl = null, creatorId = null, creatorName = null, rawJson = "{}"
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        PatreonDatabase.clearInstance()
    }

    @Test
    fun `FTS search finds post by title keyword`() = runTest {
        insertPosts()

        val results = db.postDao().search("Audio")
        assertEquals("Should find exactly 1 post matching 'Audio'", 1, results.size)
        assertEquals("222222", results[0].id)
    }

    @Test
    fun `FTS search finds posts by content keyword`() = runTest {
        insertPosts()

        val results = db.postDao().search("Mock")
        assertTrue("Should find at least 1 post matching 'Mock'", results.isNotEmpty())
    }

    @Test
    fun `FTS search returns empty list for unmatched query`() = runTest {
        insertPosts()

        val results = db.postDao().search("xyzzy_nonexistent_12345")
        assertTrue("Should return empty list for non-matching query", results.isEmpty())
    }

    @Test
    fun `FTS search handles prefix queries`() = runTest {
        insertPosts()

        val results = db.postDao().search("Imag*")
        assertTrue("Prefix search should match 'Image' posts", results.isNotEmpty())
    }

    private suspend fun insertPosts() {
        val posts = listOf(
            makePost("111111", "First Image Post", "Mock content about images and photography."),
            makePost("222222", "Audio Episode 1", "Listen to this mock audio episode."),
            makePost("333333", "Text Post with YouTube Embed", "Watch this Mock YouTube video.")
        )
        db.postDao().insertAll(posts)
    }

    private fun makePost(id: String, title: String, content: String) = PostEntity(
        id = id,
        campaignId = "camp1",
        title = title,
        content = content,
        teaserText = null,
        postType = "text_only",
        isViewable = true,
        url = null,
        publishedAt = "2024-01-01T00:00:00.000+00:00",
        editedAt = null,
        commentCount = 0,
        coverImageUrl = null,
        thumbnailUrl = null,
        embedUrl = null,
        embedSubject = null,
        rawJson = "{}"
    )
}
