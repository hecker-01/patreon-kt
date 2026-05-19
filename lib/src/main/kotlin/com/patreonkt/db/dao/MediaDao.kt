package com.patreonkt.db.dao

import androidx.room.*
import com.patreonkt.db.entities.MediaEntity

/** Data-access object for [MediaEntity]. */
@Dao
interface MediaDao {

    /** Inserts or replaces a media record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: MediaEntity)

    /** Inserts or replaces multiple media records in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(media: List<MediaEntity>)

    /** Returns all media associated with [postId]. */
    @Query("SELECT * FROM media WHERE postId = :postId")
    suspend fun getMediaForPost(postId: String): List<MediaEntity>

    /** Returns all media associated with [campaignId]. */
    @Query("SELECT * FROM media WHERE campaignId = :campaignId")
    suspend fun getMediaForCampaign(campaignId: String): List<MediaEntity>

    /** Returns the media record with [id], or null if not found. */
    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: String): MediaEntity?

    /**
     * Updates [localPath] and [downloadedAt] for an existing media record.
     * Called after a file is successfully written to disk.
     */
    @Query("UPDATE media SET localPath = :localPath, downloadedAt = :downloadedAt WHERE id = :id")
    suspend fun markDownloaded(id: String, localPath: String, downloadedAt: String)

    /** Returns the total number of stored media records. */
    @Query("SELECT COUNT(*) FROM media")
    suspend fun count(): Int

    /** Deletes a specific media record. */
    @Delete
    suspend fun delete(media: MediaEntity)
}
