package fr.arichard.lastlauncher.predict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The launcher's memory: one row per app launch, with the context it happened in
 * (hour, day of week, previous app, active trigger event) — plus one row per
 * "miss" (an app that was suggested and explicitly swiped away before launching
 * something else), stored with the same context so corrections weigh on the same
 * statistics launches feed. Kept small by pruning, never leaves the device.
 */
class UsageDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "usage.db", null, 3) {

    data class Row(
        val pkg: String,
        val ts: Long,
        val hour: Int,
        val dow: Int,
        val prevPkg: String?,
        val ctxEvent: String?,
        /** [AmbientState] snapshot (`k=v,...`); null on rows older than schema 3. */
        val state: String? = null,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE launches(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                ts INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                dow INTEGER NOT NULL,
                prev_pkg TEXT,
                ctx_event TEXT,
                state TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_launches_ts ON launches(ts)")
        createMisses(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createMisses(db)
        if (oldVersion < 3) {
            // Schema 3: the ambient-state snapshot (see AmbientState). Fresh installs
            // get the column from CREATE; a v1 database got its misses table just
            // above, so both tables exist here.
            db.execSQL("ALTER TABLE launches ADD COLUMN state TEXT")
            db.execSQL("ALTER TABLE misses ADD COLUMN state TEXT")
        }
    }

    private fun createMisses(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS misses(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pkg TEXT NOT NULL,
                ts INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                dow INTEGER NOT NULL,
                ctx_event TEXT,
                state TEXT)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_misses_ts ON misses(ts)")
    }

    fun insertLaunch(row: Row) {
        writableDatabase.insert("launches", null, ContentValues().apply {
            put("pkg", row.pkg)
            put("ts", row.ts)
            put("hour", row.hour)
            put("dow", row.dow)
            put("prev_pkg", row.prevPkg)
            put("ctx_event", row.ctxEvent)
            put("state", row.state)
        })
    }

    /**
     * Launch rows from [sinceTs] on, oldest first. [withState] = false skips the
     * ambient-state column (scoring ignores it; the export and insights read it):
     * fewer bytes off disk and one string less per row, [Row.state] then null.
     */
    fun rowsSince(sinceTs: Long, withState: Boolean = true): List<Row> {
        val rows = ArrayList<Row>(1024)
        val stateCol = if (withState) ", state" else ""
        readableDatabase.rawQuery(
            "SELECT pkg, ts, hour, dow, prev_pkg, ctx_event$stateCol FROM launches WHERE ts >= ? ORDER BY ts",
            arrayOf(sinceTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        pkg = c.getString(0),
                        ts = c.getLong(1),
                        hour = c.getInt(2),
                        dow = c.getInt(3),
                        prevPkg = if (c.isNull(4)) null else c.getString(4),
                        ctxEvent = if (c.isNull(5)) null else c.getString(5),
                        state = if (!withState || c.isNull(6)) null else c.getString(6),
                    )
                )
            }
        }
        return rows
    }

    fun totalCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM launches", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun oldestTs(): Long? {
        readableDatabase.rawQuery("SELECT MIN(ts) FROM launches", null).use { c ->
            return if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    /** Launch rows carrying an ambient-state snapshot (schema 3+), for signal health. */
    fun countWithState(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM launches WHERE state IS NOT NULL", null
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Number of launches logged since [sinceTs]. */
    fun countSince(sinceTs: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM launches WHERE ts >= ?", arrayOf(sinceTs.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * Caps the tables so they can never grow unbounded. Rows older than the
     * scoring horizon ([ScoreMath.HISTORY_DAYS]) go first — the engine never
     * reads them, so dropping them changes no score — then the row-count belts
     * cap a heavy user's log. Returns the number of rows deleted.
     */
    fun prune(now: Long): Int {
        val db = writableDatabase
        val horizon = now - RETENTION_DAYS * ScoreMath.DAY_MS
        var deleted = 0
        deleted += db.compileStatement("DELETE FROM launches WHERE ts < ?")
            .apply { bindLong(1, horizon) }.use { it.executeUpdateDelete() }
        deleted += db.compileStatement("DELETE FROM misses WHERE ts < ?")
            .apply { bindLong(1, horizon) }.use { it.executeUpdateDelete() }
        deleted += db.compileStatement(
            """DELETE FROM launches WHERE id NOT IN
               (SELECT id FROM launches ORDER BY ts DESC LIMIT $MAX_ROWS)"""
        ).use { it.executeUpdateDelete() }
        deleted += db.compileStatement(
            """DELETE FROM misses WHERE id NOT IN
               (SELECT id FROM misses ORDER BY ts DESC LIMIT $MAX_MISS_ROWS)"""
        ).use { it.executeUpdateDelete() }
        return deleted
    }

    /** Pages SQLite holds in its free list — space deletes left in the file. */
    fun freePages(): Long {
        readableDatabase.rawQuery("PRAGMA freelist_count", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /**
     * Rebuilds the file without its free pages when pruning has left at least
     * [VACUUM_FREE_PAGES] of them (SQLite never gives deleted pages back on its
     * own, so the file only ever grew). A few milliseconds on a file this
     * size; a no-op below the threshold. Returns true when it ran.
     */
    fun compactIfFragmented(): Boolean {
        if (freePages() < VACUUM_FREE_PAGES) return false
        writableDatabase.execSQL("VACUUM")
        return true
    }

    /** Every package with at least one launch or miss row. */
    fun knownPackages(): Set<String> {
        val out = HashSet<String>(64)
        readableDatabase.rawQuery(
            "SELECT DISTINCT pkg FROM launches UNION SELECT DISTINCT pkg FROM misses", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /** Drops every launch and miss row of [pkg] (uninstalled app). Returns rows deleted. */
    fun deletePackage(pkg: String): Int {
        val args = arrayOf(pkg)
        val db = writableDatabase
        return db.delete("launches", "pkg = ?", args) + db.delete("misses", "pkg = ?", args)
    }

    fun clearAll() {
        writableDatabase.delete("launches", null, null)
        writableDatabase.delete("misses", null, null)
    }

    // ---------------------------------------------------------------- misses

    fun insertMiss(row: Row) {
        writableDatabase.insert("misses", null, ContentValues().apply {
            put("pkg", row.pkg)
            put("ts", row.ts)
            put("hour", row.hour)
            put("dow", row.dow)
            put("ctx_event", row.ctxEvent)
            put("state", row.state)
        })
    }

    /** Miss rows from [sinceTs] on, oldest first; [withState] as in [rowsSince]. */
    fun missesSince(sinceTs: Long, withState: Boolean = true): List<Row> {
        val rows = ArrayList<Row>(64)
        val stateCol = if (withState) ", state" else ""
        readableDatabase.rawQuery(
            "SELECT pkg, ts, hour, dow, ctx_event$stateCol FROM misses WHERE ts >= ? ORDER BY ts",
            arrayOf(sinceTs.toString())
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        pkg = c.getString(0),
                        ts = c.getLong(1),
                        hour = c.getInt(2),
                        dow = c.getInt(3),
                        prevPkg = null,
                        ctxEvent = if (c.isNull(4)) null else c.getString(4),
                        state = if (!withState || c.isNull(5)) null else c.getString(5),
                    )
                )
            }
        }
        return rows
    }

    fun totalMissCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM misses", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    companion object {
        /** Row-count belts, kept as a cap on top of the time-based prune. */
        const val MAX_ROWS = 5000
        const val MAX_MISS_ROWS = 1500
        /** Rows older than this are deleted — equal to the scoring horizon on purpose. */
        const val RETENTION_DAYS = ScoreMath.HISTORY_DAYS
        /** Free pages (4 KB each) that trigger a VACUUM after a prune: 64 ≈ 256 KB. */
        const val VACUUM_FREE_PAGES = 64L
    }
}
