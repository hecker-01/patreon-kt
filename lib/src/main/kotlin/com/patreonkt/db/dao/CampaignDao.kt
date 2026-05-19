package com.patreonkt.db.dao

import androidx.room.*
import com.patreonkt.db.entities.CampaignEntity
import kotlinx.coroutines.flow.Flow

/** Data-access object for [CampaignEntity]. */
@Dao
interface CampaignDao {

    /** Inserts or replaces a campaign record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(campaign: CampaignEntity)

    /** Returns all campaigns as a cold [Flow] that re-emits on every change. */
    @Query("SELECT * FROM campaigns ORDER BY name ASC")
    fun getAll(): Flow<List<CampaignEntity>>

    /** Returns a snapshot of all campaigns. */
    @Query("SELECT * FROM campaigns ORDER BY name ASC")
    suspend fun getAllOnce(): List<CampaignEntity>

    /** Returns the campaign with [id], or null if not found. */
    @Query("SELECT * FROM campaigns WHERE id = :id")
    suspend fun getById(id: String): CampaignEntity?

    /** Deletes a campaign and cascades to all related posts, media, and comments. */
    @Delete
    suspend fun delete(campaign: CampaignEntity)

    /** Returns the number of stored campaigns. */
    @Query("SELECT COUNT(*) FROM campaigns")
    suspend fun count(): Int
}
