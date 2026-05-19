package com.patreonkt.db.dao

import androidx.room.*
import com.patreonkt.db.entities.PostEntity
import kotlinx.coroutines.flow.Flow

/** Data-access object for [PostEntity] and the FTS4 search index. */
@Dao
interface PostDao {

    /** Inserts or replaces a post record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(post: PostEntity)

    /** Inserts or replaces multiple posts in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(posts: List<PostEntity>)

    /** Returns all posts for a campaign as a cold [Flow] ordered newest-first. */
    @Query("SELECT * FROM posts WHERE campaignId = :campaignId ORDER BY publishedAt DESC")
    fun getPostsByCampaign(campaignId: String): Flow<List<PostEntity>>

    /** Returns a snapshot of all posts for a campaign, newest-first. */
    @Query("SELECT * FROM posts WHERE campaignId = :campaignId ORDER BY publishedAt DESC")
    suspend fun getPostsByCampaignOnce(campaignId: String): List<PostEntity>

    /** Returns the post with [id], or null if not found. */
    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun getById(id: String): PostEntity?

    /**
     * Full-text search across post title, content, and teaser text using the FTS4 virtual table.
     *
     * Supports standard SQLite FTS4 query syntax: prefix (`word*`), phrase (`"exact phrase"`),
     * and column filter (`title:word`).
     */
    @Query("""
        SELECT p.* FROM posts p
        JOIN posts_fts f ON p.rowid = f.rowid
        WHERE posts_fts MATCH :query
        ORDER BY p.publishedAt DESC
    """)
    suspend fun search(query: String): List<PostEntity>

    /** Returns the number of posts for a campaign. */
    @Query("SELECT COUNT(*) FROM posts WHERE campaignId = :campaignId")
    suspend fun countByCampaign(campaignId: String): Int

    /** Returns the total number of stored posts across all campaigns. */
    @Query("SELECT COUNT(*) FROM posts")
    suspend fun count(): Int

    /** Returns a snapshot of all posts across all campaigns. */
    @Query("SELECT * FROM posts ORDER BY publishedAt DESC")
    suspend fun getAllOnce(): List<PostEntity>

    /** Deletes a specific post. */
    @Delete
    suspend fun delete(post: PostEntity)
}
