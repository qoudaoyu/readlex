package com.readvocab.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readvocab.app.data.AppDatabase
import com.readvocab.app.data.BackupException
import com.readvocab.app.data.BackupManager
import com.readvocab.app.data.BookEntity
import com.readvocab.app.data.DailyReadingEntity
import com.readvocab.app.data.LogPoint
import com.readvocab.app.data.ReviewSource
import com.readvocab.app.data.StatsRepository
import com.readvocab.app.data.WordEntity
import com.readvocab.app.reminder.Reminders
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class V2Test {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = 24 * 3600 * 1000L

    private fun word(lemma: String, status: WordStatus, due: Long, created: Long = 0) =
        WordEntity(lemma = lemma, status = status, due = due, createdAt = created, updatedAt = created)

    @Test fun statsTodayStreakAndForecast() {
        val now = at(2026, 11, 16, 21)
        val repo = StatsRepository(Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build(), { now }, { zone })
        val words = listOf(
            word("a", WordStatus.LEARNING, now - 1000),          // overdue → today
            word("b", WordStatus.LEARNING, at(2026, 11, 17, 1)), // after midnight → tomorrow
            word("c", WordStatus.FAMILIAR, at(2026, 11, 22)),    // 6 days out
            word("d", WordStatus.MASTERED, at(2027, 1, 1)),      // beyond the forecast
            word("e", WordStatus.KNOWN, 0),
        )
        val log = listOf(
            LogPoint(at(2026, 11, 13), ReviewSource.CARD),       // gap on the 12th... streak starts 13th
            LogPoint(at(2026, 11, 14), ReviewSource.READING),
            LogPoint(at(2026, 11, 16, 0), ReviewSource.ADD),     // an add alone is not activity
            LogPoint(at(2026, 11, 16, 9), ReviewSource.CARD),
            LogPoint(at(2026, 11, 16, 10), ReviewSource.LOOKUP),
        )
        val read = listOf(
            DailyReadingEntity(LocalDateTime.of(2026, 11, 15, 0, 0).toLocalDate().toEpochDay(), 800, 600),
            DailyReadingEntity(LocalDateTime.of(2026, 11, 16, 0, 0).toLocalDate().toEpochDay(), 1200, 900),
        )
        val s = repo.build(words, log, read, totalReviews = 4, totalRead = 2000)
        assertEquals(2, s.today.reviews)
        assertEquals(1, s.today.added)
        assertEquals(1200, s.today.wordsRead)
        assertEquals(900, s.today.seconds)
        assertEquals(4, s.streak) // 13, 14, 15 (reading), 16
        assertEquals(1, s.dueNow)
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 1), s.forecast)
        assertEquals(30, s.history.size)
        assertEquals(s.today, s.history.last())
        assertEquals(4, s.inList)
        assertEquals(1, s.known)

        // Nothing yet today: the streak up to yesterday still counts.
        val s2 = repo.build(words, log.filter { it.at < at(2026, 11, 16, 0) }, read.take(1), 2, 800)
        assertEquals(3, s2.streak)
    }

    @Test fun readingIsAccumulatedPerDay() = runBlocking {
        val now = at(2026, 11, 16)
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = StatsRepository(db, { now }, { zone })
        repo.addReading(100, 30)
        repo.addReading(50, 20)
        val s = repo.observe().first()
        assertEquals(150, s.today.wordsRead)
        assertEquals(50, s.today.seconds)
        assertEquals(150, s.totalWordsRead)
    }

    @Test fun reminderTimingAndMessage() {
        val now = at(2026, 11, 16, 21)
        assertEquals(at(2026, 11, 17, 20), Reminders.nextTrigger(now, 20 * 60, zone))
        assertEquals(at(2026, 11, 16, 22), Reminders.nextTrigger(now, 22 * 60, zone))
        val repo = StatsRepository(Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build(), { now }, { zone })
        val due = repo.build(listOf(word("a", WordStatus.LEARNING, now - 1)), emptyList(), emptyList(), 0, 0)
        assertTrue(Reminders.message(due)!!.first.contains("1 个词"))
        val idle = repo.build(emptyList(), emptyList(), emptyList(), 0, 0)
        assertTrue(Reminders.message(idle)!!.first.contains("还没读"))
        val doneToday = repo.build(emptyList(), listOf(LogPoint(now - 1000, ReviewSource.CARD)), emptyList(), 1, 0)
        assertNull(Reminders.message(doneToday))
    }

    @Test fun backupRoundTrip() = runBlocking {
        val name = "backup-test.db"
        ctx.getDatabasePath(name).delete()
        File(ctx.filesDir, "books").deleteRecursively()
        val settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        settings.edit().putString("apiKey", "sk-secret").putInt("fontSizeSp", 22).putBoolean("autoStory", true).commit()

        var db = AppDatabase.create(ctx, name)
        val id = db.books().insert(BookEntity(title = "Kept", author = "", chapterCount = 1, wordCount = 3, addedAt = 1))
        File(ctx.filesDir, "books/$id").apply { mkdirs() }.resolve("0.txt").writeText("Hello there friend")
        db.words().upsert(word("plateau", WordStatus.LEARNING, 5))
        val out = ByteArrayOutputStream()
        val summary = BackupManager(ctx, db, name).export(out)
        assertEquals(1, summary.books)
        assertEquals(1, summary.words)
        val bytes = out.toByteArray()
        assertTrue("API key must not be in the backup", !String(bytes, Charsets.ISO_8859_1).contains("sk-secret"))

        // Change everything after the backup.
        db.words().delete("plateau")
        db.words().upsert(word("later", WordStatus.LEARNING, 5))
        File(ctx.filesDir, "books").deleteRecursively()
        settings.edit().putInt("fontSizeSp", 16).putString("apiKey", "sk-new").commit()

        BackupManager(ctx, db, name).restore(ByteArrayInputStream(bytes))
        db = AppDatabase.create(ctx, name)
        assertEquals(WordStatus.LEARNING, db.words().get("plateau")!!.status)
        assertNull(db.words().get("later"))
        assertEquals("Kept", db.books().get(id)!!.title)
        assertEquals("Hello there friend", File(ctx.filesDir, "books/$id/0.txt").readText())
        assertEquals(22, settings.getInt("fontSizeSp", 0))
        assertEquals(true, settings.getBoolean("autoStory", false))
        assertEquals("sk-new", settings.getString("apiKey", null)) // the key on this phone is kept
        db.close()
    }

    @Test fun restoreRejectsForeignAndUnsafeFiles() = runBlocking {
        val name = "backup-reject.db"
        ctx.getDatabasePath(name).delete()
        val db = AppDatabase.create(ctx, name)
        db.words().upsert(word("keep", WordStatus.LEARNING, 5))
        fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        }.toByteArray()
        val manifest = JSONObject().put("format", 1).put("dbVersion", 3).toString().toByteArray()
        val cases = listOf(
            zip("hello.txt" to "hi".toByteArray()),
            zip("manifest.json" to manifest, "readvocab.db" to "not a database".toByteArray()),
            zip("manifest.json" to JSONObject().put("format", 1).put("dbVersion", 99).toString().toByteArray(), "readvocab.db" to ByteArray(10)),
            zip("manifest.json" to manifest, "books/../../evil.txt" to "x".toByteArray(), "readvocab.db" to ByteArray(10)),
        )
        for (c in cases) {
            try {
                BackupManager(ctx, db, name).restore(ByteArrayInputStream(c))
                fail("should have been rejected")
            } catch (_: BackupException) {
            }
        }
        // Rejected restores leave the live data alone.
        assertEquals(WordStatus.LEARNING, db.words().get("keep")!!.status)
        db.close()
    }

    @Test fun migrates2to3() = runBlocking {
        val schema = JSONObject(File("schemas/com.readvocab.app.data.AppDatabase/2.json").readText()).getJSONObject("database")
        val file = ctx.getDatabasePath("migrate-23.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO words (lemma, status, stability, difficulty, due, lastReview, reps, lapses, lookups, passes, createdAt, updatedAt) VALUES ('plateau', 'LEARNING', 1, 5, 0, 0, 1, 0, 1, 0, ${at(2026, 11, 10)}, 0)")
            db.execSQL("INSERT INTO words (lemma, status, stability, difficulty, due, lastReview, reps, lapses, lookups, passes, createdAt, updatedAt) VALUES ('the', 'KNOWN', 0, 0, 0, 0, 0, 0, 0, 3, 0, 0)")
            db.execSQL("INSERT INTO review_log (lemma, rating, source, at) VALUES ('plateau', 3, 'CARD', ${at(2026, 11, 11)})")
            db.version = 2
        }
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
        val now = at(2026, 11, 16)
        val stats = StatsRepository(room, { now }, { zone }).observe().first()
        assertEquals(1, stats.history.sumOf { it.added }) // backfilled from the word's createdAt
        assertEquals(1, stats.history.sumOf { it.reviews })
        assertEquals(1, stats.totalReviews)
        room.close()
    }
}
