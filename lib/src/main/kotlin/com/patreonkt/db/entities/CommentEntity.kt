package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted comment record.
 *
 * @property id Patreon comment identifier.
 * @property postId Foreign key to the parent [PostEntity].
 * @property authorId Patreon user identifier of the commenter.
 * @property authorName Display name of the commenter.
 * @property body Comment text (may contain HTML).
 * @property publishedAt ISO-8601 publication timestamp.
 * @property parentCommentId For threaded replies, the identifier of the parent comment; null for
 *   top-level comments.
 * @property rawJson Full Patreon JSON-API response fragment.
 */
@Entity(
    tableName = "comments",
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("postId")]
)
data class CommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String?,
    val authorName: String?,
    val body: String?,
    val publishedAt: String?,
    val parentCommentId: String?,
    val rawJson: String
)
