package com.patreonkt.db.dao

import androidx.room.*
import com.patreonkt.db.entities.PostTagCrossRef
import com.patreonkt.db.entities.TagEntity

/** Data-access object for [TagEntity] and [PostTagCrossRef]. */
@Dao
interface TagDao {

    /** Inserts or ignores a tag (preserves existing records). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity)

    /** Links a post to a tag. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(ref: PostTagCrossRef)

    /** Returns all tags associated with [postId]. */
    @Query("""
        SELECT t.* FROM tags t
        JOIN post_tag_cross_refs r ON t.id = r.tagId
        WHERE r.postId = :postId
    """)
    suspend fun getTagsForPost(postId: String): List<TagEntity>

    /** Returns all post IDs that carry [tagId]. */
    @Query("SELECT postId FROM post_tag_cross_refs WHERE tagId = :tagId")
    suspend fun getPostIdsForTag(tagId: String): List<String>
}
