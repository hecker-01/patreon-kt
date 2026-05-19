package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Patreon post tag / label.
 *
 * @property id Unique tag identifier (typically the normalized tag text).
 * @property name Display name of the tag.
 */
@Entity(tableName = "tags")
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)
