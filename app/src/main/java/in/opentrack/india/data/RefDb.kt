package `in`.opentrack.india.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import `in`.opentrack.india.BuildConfig
import `in`.opentrack.india.core.RailTime

/**
 * The on-device railway timetable: a read-only SQLite file that ships inside the APK.
 *
 * Why not ship the JSON and parse it? 163 MB of SQL text / 90 MB of JSON versus 14 MB of SQLite:
 * the indexed file is simultaneously smaller *after* gzip and 3 orders of magnitude faster to ask a
 * question of. The cost is a one-time extraction at first launch, which is what [prepare] does.
 *
 * Lifecycle rules that matter:
 *  - extraction goes to `filesDir`, never `cacheDir` (the OS must not evict the thing the app needs
 *    to function offline), and is written to `*.tmp` + rename so a killed mid-extract never leaves a
 *    half file that opens as a valid-but-empty DB;
 *  - the file is validated (`quick_check` + schema version) before being adopted; a mismatch deletes
 *    and re-extracts, so an APK downgrade/upgrade can never leave the app querying a stale schema;
 *  - opened READONLY. A read-only connection cannot be corrupted by a battery-pull during a write,
 *    which on a train with a flat phone is a real scenario.
 */
class RefDb private constructor(private val db: SQLiteDatabase) {

    // Declared on the class, not inside the companion: a type nested in a companion object is not
    // reachable as `RefDb.State`, which is how every caller writes it.
    enum class State { READY, NEEDS_EXTRACT, EXTRACTING, BROKEN }


    /** Run [block] against the read-only handle. All app queries go through here. */
    fun <T> use(block: (SQLiteDatabase) -> T): T = synchronized(this) { block(db) }

    /** Convenience for the very common "map a cursor into a list" case; closes the cursor. */
    fun <T> query(sql: String, args: Array<String>? = null, map: (Cursor) -> T): T =
        synchronized(this) {
            db.rawQuery(sql, args).use { c -> map(c) }
        }

    fun firstInt(sql: String, args: Array<String>? = null): Int? =
        query(sql, args) { c -> if (c.moveToNext()) c.getInt(0) else null }

    fun close() = runCatching { db.close() }

    companion object {
        private const val TAG = "RefDb"
        /**
         * Not `.gz`, on purpose. aapt recognises `.gz` assets and renames them at packaging time — the
         * entry in the APK becomes `ref/refdb.db` — so `open("ref/refdb.db.gz")` throws
         * FileNotFoundException in a build that passed every task and every unit test. The reports on
         * that behaviour even disagree about whether the bytes stay gzip-framed, which is a worse kind
         * of unknown than an unusual extension. `.dbgz` means nothing to the toolchain: the name we ask
         * for is the name that ships, and GZIPInputStream below is the only decompression in the path.
         * tools/ci and the CI job assert that this string and the file the ETL wrote agree.
         */
        const val ASSET_NAME = "ref/refdb.dbgz"
        private const val DB_FILE = "refdb.db"
        private const val EXTRACT_BUFFER = 64 * 1024

        @Volatile
        private var opened: RefDb? = null

        fun dbFile(context: Context): File = File(context.filesDir, DB_FILE)

        fun state(context: Context): State {
            val f = dbFile(context)
            if (!f.exists() || f.length() < 4096) return State.NEEDS_EXTRACT
            // `REFDB_SCHEMA_VERSION` is a `long` buildConfigField (the DB column is INTEGER), so the
            // comparison happens on the wider type rather than by shrinking the constant.
            val version = peekVersion(f) ?: return State.BROKEN
            return if (version.toLong() == BuildConfig.REFDB_SCHEMA_VERSION) State.READY else State.NEEDS_EXTRACT
        }

        /**
         * Extract (if needed) and open. Blocking — call from an IO dispatcher, and show progress on
         * first launch (the user is waiting for a working app, and a silent 2 s freeze on a low-end
         * device reads as a crash).
         */
        fun prepare(context: Context, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): State {
            val file = dbFile(context)
            when (state(context)) {
                State.READY -> Unit
                State.EXTRACTING -> return State.EXTRACTING
                State.NEEDS_EXTRACT, State.BROKEN -> {
                    if (!extract(context, file, onProgress)) return State.BROKEN
                }
            }
            return State.READY
        }

        private fun extract(context: Context, file: File, onProgress: (Long, Long) -> Unit): Boolean {
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching { if (tmp.exists()) tmp.delete() }
            return try {
                context.assets.open(ASSET_NAME).use { raw ->
                    GZIPInputStream(raw, EXTRACT_BUFFER).use { gz ->
                        FileOutputStream(tmp).use { out ->
                            val total = compressedHint(context)
                            val buf = ByteArray(EXTRACT_BUFFER)
                            var done = 0L
                            while (true) {
                                val n = gz.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                // `total` is the *uncompressed* size we recorded in the manifest; if
                                // it is unavailable we report bytes read so the bar still animates.
                                onProgress(done, if (total > 0) total else (done + 1))
                            }
                            out.flush()
                        }
                    }
                }
                if (!isValid(tmp)) {
                    Log.w(TAG, "extracted db failed quick_check; discarding")
                    runCatching { tmp.delete() }
                    false
                } else {
                    runCatching { if (file.exists()) file.delete() }
                    if (!tmp.renameTo(file)) {
                        Log.w(TAG, "rename failed, copying in place")
                        tmp.copyTo(file, overwrite = true)
                        runCatching { tmp.delete() }
                    }
                    true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "extraction failed", t)
                runCatching { tmp.delete() }
                false
            }
        }

        /** Uncompressed size of the bundled DB, from the manifest the ETL writes next to it. */
        private fun compressedHint(context: Context): Long = try {
            val text = context.assets.open("ref/refdb-manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val m = Regex("\"size_bytes\"\\s*:\\s*(\\d+)").find(text)
            m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        } catch (t: Throwable) {
            0L
        }

        /** A truncated gzip stream still opens in SQLite; these two checks are what stop us from
         *  adopting a half-written database that silently returns empty search results. */
        private fun isValid(file: File): Boolean {
            if (!file.exists() || file.length() < 4096) return false
            return try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                    val sane = d.rawQuery("PRAGMA quick_check", null).use { c ->
                        c.moveToNext() && c.getString(0) == "ok"
                    }
                    sane && d.versionOkForSchema()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "db not usable: ${t.message}")
                false
            }
        }

        private fun peekVersion(file: File): Int? = try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                d.metaInt("schema_version")
            }
        } catch (t: Throwable) {
            null
        }

        private fun SQLiteDatabase.versionOkForSchema(): Boolean =
            (metaInt("schema_version") ?: -1).toLong() == BuildConfig.REFDB_SCHEMA_VERSION

        fun openOrCreate(context: Context): RefDb {
            opened?.let { return it }
            synchronized(this) {
                opened?.let { return it }
                if (prepare(context) != State.READY) {
                    throw IllegalStateException("reference database not ready")
                }
                val db = SQLiteDatabase.openDatabase(
                    dbFile(context).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                return RefDb(db).also { opened = it }
            }
        }

        /** True when the bundled timetable is present and usable — every offline feature depends on it. */
        fun isUsable(context: Context): Boolean = state(context) == State.READY

        fun closeShared() {
            synchronized(this) {
                opened?.close()
                opened = null
            }
        }
    }
}

/** Small metadata accessor shared by RefDb validation and the UI's "data as of" line. */
fun SQLiteDatabase.metaInt(key: String): Int? =
    runCatching {
        rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToNext()) c.getString(0).toIntOrNull() else null
        }
    }.getOrNull()

fun SQLiteDatabase.metaString(key: String): String? =
    runCatching {
        rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToNext()) c.getString(0) else null
        }
    }.getOrNull()

/**
 * The DB has no notion of "today" — injecting one here (rather than calling System.currentTimeMillis
 * inside queries) is what makes the search window testable: a unit test pins the clock and asserts
 * that a 23:50 train on D+1 is inside a 23:40-on-D search window.
 */
fun interface Clock {
    fun nowMillis(): Long
    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** IST epoch-day for the moment the search was issued. */
fun Clock.istEpochDay(): Long = RailTime.istEpochDay(nowMillis())
