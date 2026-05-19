package com.patreonkt.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.patreonkt.db.dao.*
import com.patreonkt.db.entities.*

/**
 * Room database holding all archived Patreon content.
 *
 * Obtain an instance via [getInstance]. The database is exposed as a public API so host
 * applications can query and display content directly through the DAO interfaces.
 *
 * ### Direct access example
 * ```kotlin
 * val db = archiver.database
 * val posts = db.postDao().getPostsByCampaignOnce("12345")
 * val results = db.postDao().search("my query")
 * ```
 */
@Database(
    entities = [
        CampaignEntity::class,
        PostEntity::class,
        PostFts::class,
        MediaEntity::class,
        TagEntity::class,
        PostTagCrossRef::class,
        CommentEntity::class,
        ProductEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class PatreonDatabase : RoomDatabase() {

    /** Access campaign records. */
    abstract fun campaignDao(): CampaignDao

    /** Access post records and the FTS4 full-text search index. */
    abstract fun postDao(): PostDao

    /** Access media records. */
    abstract fun mediaDao(): MediaDao

    /** Access tag records and post-tag associations. */
    abstract fun tagDao(): TagDao

    /** Access comment records. */
    abstract fun commentDao(): CommentDao

    /** Access shop product records. */
    abstract fun productDao(): ProductDao

    companion object {
        @Volatile
        private var INSTANCE: PatreonDatabase? = null

        /**
         * Returns the singleton [PatreonDatabase], creating it if necessary.
         *
         * @param context Application context.
         * @param name Database file name. Override for in-memory testing (`":memory:"`).
         */
        fun getInstance(context: Context, name: String = "patreon.db"): PatreonDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, name).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context, name: String): PatreonDatabase =
            if (name == ":memory:") {
                Room.inMemoryDatabaseBuilder(context.applicationContext, PatreonDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            } else {
                Room.databaseBuilder(context.applicationContext, PatreonDatabase::class.java, name)
                    .build()
            }

        /** Clears the cached singleton instance. Use in tests only. */
        @JvmStatic
        fun clearInstance() {
            synchronized(this) { INSTANCE = null }
        }
    }
}
