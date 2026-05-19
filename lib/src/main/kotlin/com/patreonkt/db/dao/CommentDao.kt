package com.patreonkt.db.dao

import androidx.room.*
import com.patreonkt.db.entities.CommentEntity

/** Data-access object for [CommentEntity]. */
@Dao
interface CommentDao {

    /** Inserts or replaces a comment record. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: CommentEntity)

    /** Inserts or replaces multiple comment records in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(comments: List<CommentEntity>)

    /** Returns all top-level comments for [postId] ordered by publication date. */
    @Query("SELECT * FROM comments WHERE postId = :postId AND parentCommentId IS NULL ORDER BY publishedAt ASC")
    suspend fun getTopLevelComments(postId: String): List<CommentEntity>

    /** Returns all replies to [parentCommentId] ordered by publication date. */
    @Query("SELECT * FROM comments WHERE parentCommentId = :parentCommentId ORDER BY publishedAt ASC")
    suspend fun getReplies(parentCommentId: String): List<CommentEntity>
}
