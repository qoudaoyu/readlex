package com.readvocab.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.readvocab.core.vocab.WordStatus

class Converters {
    @TypeConverter fun statusToString(s: WordStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): WordStatus = WordStatus.valueOf(s)
    @TypeConverter fun sourceToString(s: ReviewSource): String = s.name
    @TypeConverter fun stringToSource(s: String): ReviewSource = ReviewSource.valueOf(s)
}

@Database(
    entities = [BookEntity::class, ChapterEntity::class, WordEntity::class, EncounterEntity::class, ReviewLogEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun words(): WordDao
    abstract fun encounters(): EncounterDao
    abstract fun reviewLog(): ReviewLogDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "readvocab.db").build()
    }
}
