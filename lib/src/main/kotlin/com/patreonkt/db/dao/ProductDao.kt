package com.patreonkt.db.dao

import androidx.room.*
import com.patreonkt.db.entities.ProductEntity

/** Data-access object for [ProductEntity]. */
@Dao
interface ProductDao {

    /** Inserts or replaces a product record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(product: ProductEntity)

    /** Returns all products for [campaignId]. */
    @Query("SELECT * FROM products WHERE campaignId = :campaignId ORDER BY name ASC")
    suspend fun getProductsByCampaign(campaignId: String): List<ProductEntity>

    /** Returns the product with [id], or null if not found. */
    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getById(id: String): ProductEntity?

    /** Returns the total number of stored products. */
    @Query("SELECT COUNT(*) FROM products")
    suspend fun count(): Int
}
