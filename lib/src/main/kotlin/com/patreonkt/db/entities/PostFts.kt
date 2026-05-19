package com.patreonkt.db.entities

import androidx.room.Entity
import androidx.room.Fts4

/**
 * FTS4 virtual table mirroring [PostEntity] content columns.
 *
 * Room generates `INSERT`/`UPDATE`/`DELETE` triggers automatically so this table stays in sync
 * with the `posts` table. Query it with the SQLite `MATCH` operator via [com.patreonkt.db.dao.PostDao.search].
 */
@Entity(tableName = "posts_fts")
@Fts4(contentEntity = PostEntity::class)
data class PostFts(
    val title: String,
    val content: String?,
    val teaserText: String?
)
