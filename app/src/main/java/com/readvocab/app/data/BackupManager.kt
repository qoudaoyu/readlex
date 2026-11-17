package com.readvocab.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(message: String) : Exception(message)

data class BackupSummary(val books: Int, val words: Int, val createdAt: Long)

/**
 * Backs up everything the user created (database, book text, reading settings) into one zip file,
 * and restores it. AI provider settings, including the API key, are never written to the backup,
 * because backup files tend to end up in cloud drives and chat apps.
 *
 * Restore replaces the database file underneath Room, so the caller must restart the process
 * right after a successful restore (see [restartApp]).
 */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val dbName: String = AppDatabase.NAME,
) {
    private val booksDir get() = File(context.filesDir, "books")
    private val settingsPrefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val appPrefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    val lastBackupAt: Long get() = appPrefs.getLong(KEY_LAST_BACKUP, 0)

    suspend fun export(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri) ?: throw BackupException("无法写入备份文件")
        out.use { export(it) }
    }

    suspend fun export(out: OutputStream): BackupSummary = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into the main file so a single file holds everything.
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val books = database.books().count()
        val words = database.words().count()
        val now = System.currentTimeMillis()
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("dbVersion", DB_VERSION)
                .put("createdAt", now)
                .put("books", books)
                .put("words", words)
            zip.putText(MANIFEST, manifest.toString(2))
            zip.putText(SETTINGS, settingsToJson().toString(2))
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            context.getDatabasePath(dbName).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            booksDir.walkTopDown().filter { it.isFile }.forEach { f ->
                zip.putNextEntry(ZipEntry("books/" + f.relativeTo(booksDir).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        appPrefs.edit().putLong(KEY_LAST_BACKUP, now).apply()
        BackupSummary(books, words, now)
    }

    suspend fun restore(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw BackupException("无法读取备份文件")
        input.use { restore(it) }
    }

    /**
     * Unpacks into a staging folder first and only touches live data once the backup checks out,
     * so a corrupt or foreign file leaves the current data untouched.
     */
    suspend fun restore(input: InputStream): BackupSummary = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        try {
            unzipTo(input, staging)
            val manifestFile = File(staging, MANIFEST)
            if (!manifestFile.exists() || !File(staging, DB_ENTRY).exists()) throw BackupException("这不是阅读背单词的备份文件")
            val manifest = JSONObject(manifestFile.readText())
            if (manifest.optInt("format") != FORMAT) throw BackupException("备份格式不支持")
            if (manifest.optInt("dbVersion") > DB_VERSION) throw BackupException("这个备份来自更新版本的 App，请先升级 App")
            checkSqlite(File(staging, DB_ENTRY))

            database.close()
            val dbFile = context.getDatabasePath(dbName)
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            File(staging, DB_ENTRY).copyTo(dbFile, overwrite = true)

            booksDir.deleteRecursively()
            File(staging, "books").takeIf { it.exists() }?.copyRecursively(booksDir, overwrite = true)

            File(staging, SETTINGS).takeIf { it.exists() }?.let { restoreSettings(JSONObject(it.readText())) }
            // Restored data already contains whatever sample the user kept or deleted.
            appPrefs.edit().putBoolean("sampleImported", true).commit()

            BackupSummary(manifest.optInt("books"), manifest.optInt("words"), manifest.optLong("createdAt"))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun unzipTo(input: InputStream, dir: File) {
        val root = dir.canonicalFile
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                val allowed = name == MANIFEST || name == SETTINGS || name == DB_ENTRY || name.startsWith("books/")
                if (!allowed || entry.isDirectory) continue
                val target = File(root, name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw BackupException("备份文件已损坏")
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_UNPACKED) throw BackupException("备份文件太大")
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    private fun checkSqlite(file: File) {
        val header = file.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
        if (!String(header, Charsets.US_ASCII).startsWith("SQLite format 3")) throw BackupException("备份里的数据库已损坏")
    }

    private fun settingsToJson(): JSONObject {
        val json = JSONObject()
        settingsPrefs.all.forEach { (key, value) ->
            if (key in AI_KEYS || value == null) return@forEach
            val typed = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                else -> return@forEach
            }
            json.put(key, typed)
        }
        return json
    }

    private fun restoreSettings(json: JSONObject) {
        val edit = settingsPrefs.edit()
        settingsPrefs.all.keys.filter { it !in AI_KEYS }.forEach { edit.remove(it) }
        json.keys().forEach { key ->
            if (key in AI_KEYS) return@forEach
            val o = json.optJSONObject(key) ?: return@forEach
            when (o.optString("t")) {
                "b" -> edit.putBoolean(key, o.getBoolean("v"))
                "i" -> edit.putInt(key, o.getInt("v"))
                "l" -> edit.putLong(key, o.getLong("v"))
                "f" -> edit.putFloat(key, o.getDouble("v").toFloat())
                "s" -> edit.putString(key, o.getString("v"))
            }
        }
        edit.commit()
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name))
        write(text.toByteArray())
        closeEntry()
    }

    companion object {
        const val FORMAT = 1
        const val DB_VERSION = AppDatabase.VERSION
        const val MANIFEST = "manifest.json"
        const val SETTINGS = "settings.json"
        const val DB_ENTRY = "readvocab.db"
        const val KEY_LAST_BACKUP = "lastBackupAt"
        private const val MAX_UNPACKED = 2L * 1024 * 1024 * 1024
        /** Provider settings stay on this phone: the key is a secret, and the rest only makes sense with it. */
        val AI_KEYS = setOf("aiType", "apiKey", "model", "baseUrl")

        fun defaultFileName(now: Long = System.currentTimeMillis()): String {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))
            return "阅读背单词备份-$d.zip"
        }

        /** Relaunches the app in a fresh process so Room reopens the restored database. */
        fun restartApp(context: Context) {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            Runtime.getRuntime().exit(0)
        }
    }
}
