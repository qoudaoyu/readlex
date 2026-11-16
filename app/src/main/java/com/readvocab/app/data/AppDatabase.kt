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
    entities = [BookEntity::class, ChapterEntity::class, WordEntity::class, EncounterEntity::class, ReviewLogEntity::class, DailyReadingEntity::class],
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

    companion object {
        const val VERSION = 3
        const val NAME = "readvocab.db"

        fun create(context: Context, name: String = NAME): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
    }
}
