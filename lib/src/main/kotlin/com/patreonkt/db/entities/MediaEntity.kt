package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted media record for images, audio, video, attachments, and embeds.
 *
 * @property id Patreon media identifier.
 * @property postId Foreign key to [PostEntity]; null for campaign-level media.
 * @property campaignId Campaign this media belongs to.
 * @property mediaType Broad [com.patreonkt.MediaType] category as a string.
 * @property filename Original filename from Patreon.
 * @property mimeType MIME type string (e.g. `"image/jpeg"`).
 * @property url Remote download URL.
 * @property localPath Absolute path on device storage after download; null if not yet downloaded.
 * @property downloadedAt ISO-8601 timestamp of when the file was saved locally.
 * @property fileSizeBytes File size in bytes; null if unknown.
 * @property width Image/video width in pixels; null for non-visual media.
 * @property height Image/video height in pixels; null for non-visual media.
 * @property durationMs Audio/video duration in milliseconds; null for static media.
 */
@Entity(
    tableName = "media",
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("postId"), Index("campaignId")]
)
data class MediaEntity(
    @PrimaryKey val id: String,
    val postId: String?,
    val campaignId: String?,
    val mediaType: String,
    val filename: String?,
    val mimeType: String?,
    val url: String?,
    val localPath: String?,
    val downloadedAt: String?,
    val fileSizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?
)
