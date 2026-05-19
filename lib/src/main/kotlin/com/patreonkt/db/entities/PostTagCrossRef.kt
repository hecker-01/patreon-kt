package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many join table between [PostEntity] and [TagEntity].
 */
@Entity(
    tableName = "post_tag_cross_refs",
    primaryKeys = ["postId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = PostEntity::class,
            parentColumns = ["id"],
            childColumns = ["postId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("postId"), Index("tagId")]
)
data class PostTagCrossRef(
    val postId: String,
    val tagId: String
)
