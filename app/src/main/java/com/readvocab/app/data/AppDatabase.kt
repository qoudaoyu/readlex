package com.readvocab.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.readvocab.core.vocab.WordStatus

class Converters {
    @TypeConverter fun statusToString(s: WordStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): WordStatus = WordStatus.valueOf(s)
    @TypeConverter fun sourceToString(s: ReviewSource): String = s.name
    @TypeConverter fun stringToSource(s: String): ReviewSource = ReviewSource.valueOf(s)
}

@Database(
    entities = [BookEntity::class, ChapterEntity::class, WordEntity::class, EncounterEntity::class, ReviewLogEntity::class, DailyReadingEntity::class, FeedEntity::class, FeedItemEntity::class, ArticleCheckEntity::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun words(): WordDao
    abstract fun encounters(): EncounterDao
    abstract fun reviewLog(): ReviewLogDao
    abstract fun dailyReading(): DailyReadingDao
    abstract fun feeds(): FeedDao
    abstract fun checks(): ArticleCheckDao

    companion object {
        const val VERSION = 6
        const val NAME = "readvocab.db"

        val ALL_MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun create(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN kind TEXT NOT NULL DEFAULT 'BOOK'")
                db.execSQL("UPDATE books SET kind = 'STORY' WHERE author LIKE 'AI 复习短文%'")
            }
        }

        /** Adds reading statistics, and backfills "added to word list" events so history charts start full. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `daily_reading` (`day` INTEGER NOT NULL, `words` INTEGER NOT NULL, `seconds` INTEGER NOT NULL, PRIMARY KEY(`day`))")
                db.execSQL(
                    "INSERT INTO review_log (lemma, rating, source, at) " +
                        "SELECT lemma, 1, 'ADD', createdAt FROM words WHERE status IN ('LEARNING', 'FAMILIAR', 'MASTERED')"
                )
            }
        }

        /** Web articles (source URL on books) and news feeds. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS `feeds` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `site` TEXT NOT NULL, `position` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `lastFetchedAt` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`url`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `feed_items` (`link` TEXT NOT NULL, `feedUrl` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`link`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_items_feedUrl` ON `feed_items` (`feedUrl`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_feed_items_publishedAt` ON `feed_items` (`publishedAt`)")
            }
        }

        /** Article standfirst and publication date, for the magazine-style article header. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN excerpt TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN publishedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Background difficulty checks of news articles. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `article_checks` (`link` TEXT NOT NULL, `status` TEXT NOT NULL, `words` INTEGER NOT NULL, `lemmas` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`link`))")
            }
        }
    }
}
