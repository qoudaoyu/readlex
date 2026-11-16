package com.readvocab.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readvocab.app.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Opens a database created with the version-1 schema (what 0.1–0.3 installed) using the current code. */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @Test fun migrates1to2() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val schema = JSONObject(File("schemas/com.readvocab.app.data.AppDatabase/1.json").readText()).getJSONObject("database")
        val file = ctx.getDatabasePath("migrate-test.db").apply { parentFile?.mkdirs(); delete() }
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
            db.execSQL("INSERT INTO books (title, author, chapterCount, wordCount, addedAt, lastReadAt, chapterIndex, paragraphIndex) VALUES ('Old', '', 1, 10, 1, 0, 0, 0)")
            db.execSQL("INSERT INTO books (title, author, chapterCount, wordCount, addedAt, lastReadAt, chapterIndex, paragraphIndex) VALUES ('复习：X', 'AI 复习短文 · 8 个目标词', 1, 10, 2, 0, 0, 0)")
            db.version = 1
        }
        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(AppDatabase.MIGRATION_1_2).build()
        val books = room.books()
        assertEquals(false, books.get(1)!!.isStory)
        assertEquals(true, books.get(2)!!.isStory)
        room.close()
    }
}
