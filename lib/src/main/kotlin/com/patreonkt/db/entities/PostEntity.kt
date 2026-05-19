package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted post record.
 *
 * @property id Patreon post identifier.
 * @property campaignId Foreign key to [CampaignEntity].
 * @property title Post title.
 * @property content Full post body (HTML or plain text).
 * @property teaserText Truncated preview text shown to non-patrons.
 * @property postType Patreon post type string (e.g. `"text_only"`, `"image_file"`, `"audio_file"`).
 * @property isViewable Whether the authenticated user can view the full post.
 * @property url Canonical post URL.
 * @property publishedAt ISO-8601 publication timestamp.
 * @property editedAt ISO-8601 last-edit timestamp.
 * @property commentCount Number of comments at last fetch.
 * @property coverImageUrl Post cover/hero image URL.
 * @property thumbnailUrl Small thumbnail image URL.
 * @property embedUrl Embedded media URL (YouTube, SoundCloud, etc.).
 * @property embedSubject Embed provider descriptor (e.g. `"YouTube"`).
 * @property rawJson Full Patreon JSON-API response for sidecar output.
 */
@Entity(
    tableName = "posts",
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
data class PostEntity(
    @PrimaryKey val id: String,
    val campaignId: String,
    val title: String,
    val content: String?,
    val teaserText: String?,
    val postType: String,
    val isViewable: Boolean,
    val url: String?,
    val publishedAt: String?,
    val editedAt: String?,
    val commentCount: Int = 0,
    val coverImageUrl: String?,
    val thumbnailUrl: String?,
    val embedUrl: String?,
    val embedSubject: String?,
    val rawJson: String
)
