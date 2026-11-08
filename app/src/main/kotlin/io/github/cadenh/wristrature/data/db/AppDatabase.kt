package io.github.cadenh.wristrature.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A book that is or was on the watch. [id] is the KOReader document ID: the partial MD5 of the original file. */
@Entity(tableName = "books", indices = [Index("lastOpenedAt")])
data class BookEntity(
    @PrimaryKey val id: String,
    /** MD5 of the original file name, used by servers that match documents by name. */
    val nameId: String,
    val title: String,
    /** Authors joined with ", ". */
    val authors: String,
    val series: String,
    /** Folder name under the library directory. Same as [id]. */
    val dir: String,
    val totalChars: Long,
    val chapterCount: Int,
    val fileSize: Long,
    val addedAt: Long,
    /** 0 = never opened. */
    val lastOpenedAt: Long,
    val finished: Boolean,
    /** False once the file was removed to free space. The position and finished mark are kept. */
    val onWatch: Boolean,
    val demo: Boolean,
    val opdsId: String?,
    /** The `updated` timestamp from the catalog, used to detect that a book changed on the server. */
    val opdsUpdated: String?,
    val opdsFileLength: Long?,
)

/** Reading position in a book, in both the app's own terms and KOReader's. */
@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val bookId: String,
    val chapter: Int,
    val block: Int,
    val offset: Int,
    /** 0.0 to 1.0, rounded to six decimals. */
    val percent: Double,
    /** KOReader XPointer for this position. */
    val progress: String,
    val updatedAt: Long,
    /** True while this position has not reached the server. */
    val unsent: Boolean,
    /** When the server was last asked about this book. 0 = never. */
    val lastCheckedAt: Long,
)

/** A position the reader bookmarked. Bookmarks are local and are not sent to the sync server. */
@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapter: Int,
    val block: Int,
    val offset: Int,
    val percent: Double,
    /** Short chapter name shown in the list. */
    val title: String,
    /** First words at the bookmarked position. */
    val snippet: String,
    val createdAt: Long,
)

data class BookWithPosition(
    @Embedded val book: BookEntity,
    @Relation(parentColumn = "id", entityColumn = "bookId") val position: PositionEntity?,
)

@Dao
interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPosition(p: PositionEntity)

    @Query("SELECT * FROM books ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<BookWithPosition>>

    /** Started, unfinished books still on the watch, most recently read first. Feeds the Home carousel. */
    @Query("SELECT * FROM books WHERE lastOpenedAt > 0 AND finished = 0 AND onWatch = 1 ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BookWithPosition>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: String): BookEntity?

    @androidx.room.Transaction
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun bookWithPosition(id: String): BookWithPosition?

    @Query("SELECT * FROM positions WHERE bookId = :id")
    suspend fun position(id: String): PositionEntity?

    @Query("SELECT * FROM positions WHERE unsent = 1")
    suspend fun unsentPositions(): List<PositionEntity>

    @Query("UPDATE books SET lastOpenedAt = :now WHERE id = :id")
    suspend fun markOpened(id: String, now: Long)

    @Query("UPDATE books SET finished = :finished WHERE id = :id")
    suspend fun setFinished(id: String, finished: Boolean)

    @Query("UPDATE books SET onWatch = :onWatch WHERE id = :id")
    suspend fun setOnWatch(id: String, onWatch: Boolean)

    @Query("UPDATE positions SET unsent = :unsent WHERE bookId = :id")
    suspend fun setUnsent(id: String, unsent: Boolean)

    @Query("UPDATE positions SET unsent = 0 WHERE bookId = :id AND updatedAt = :updatedAt")
    suspend fun markSent(id: String, updatedAt: Long)

    @Query("UPDATE positions SET lastCheckedAt = :now WHERE bookId = :id")
    suspend fun setChecked(id: String, now: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("DELETE FROM positions WHERE bookId = :id")
    suspend fun deletePosition(id: String)

    @Insert
    suspend fun insertBookmark(b: BookmarkEntity)

    @Query("SELECT * FROM bookmarks ORDER BY bookId, percent")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY percent")
    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY percent")
    suspend fun bookmarks(bookId: String): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarks(bookId: String)

    @Query("SELECT * FROM books WHERE opdsId = :opdsId LIMIT 1")
    suspend fun byOpdsId(opdsId: String): BookEntity?
}

@Database(entities = [BookEntity::class, PositionEntity::class, BookmarkEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    companion object {
        /** Adds the bookmarks table (schema version 2). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, " +
                        "`chapter` INTEGER NOT NULL, `block` INTEGER NOT NULL, `offset` INTEGER NOT NULL, `percent` REAL NOT NULL, " +
                        "`title` TEXT NOT NULL, `snippet` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "wristrature.db").addMigrations(MIGRATION_1_2).build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
}
