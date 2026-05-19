package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted shop product record.
 *
 * @property id Patreon product identifier.
 * @property campaignId Foreign key to [CampaignEntity].
 * @property name Product display name.
 * @property description Product description text.
 * @property url Canonical product URL.
 * @property priceCents Price in the smallest currency unit (e.g. cents for USD).
 * @property currency Currency code.
 * @property publishedAt ISO-8601 publish timestamp.
 * @property thumbnailUrl Product thumbnail image URL.
 * @property rawJson Full Patreon JSON-API response for sidecar output.
 */
@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = CampaignEntity::class,
            parentColumns = ["id"],
            childColumns = ["campaignId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("campaignId")]
)
data class ProductEntity(
    @PrimaryKey val id: String,
    val campaignId: String,
    val name: String,
    val description: String?,
    val url: String?,
    val priceCents: Int?,
    val currency: String?,
    val publishedAt: String?,
    val thumbnailUrl: String?,
    val rawJson: String
)
