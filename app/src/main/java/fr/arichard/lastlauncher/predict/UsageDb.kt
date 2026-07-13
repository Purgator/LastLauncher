package fr.arichard.lastlauncher.predict

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The launcher's memory: one row per app launch, with the context it happened in
 * (hour, day of week, previous app, active trigger event). Kept small by pruning,
 * never leaves the device.
 */
class UsageDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "usage.db", null, 1) {

    data class Row(
        val pkg: String,
        val ts: Long,
        val hour: Int,
        val dow: Int,
        val prevPkg: String?,
        val ctxEvent: String?,
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
                ctx_event TEXT)"""
        )
        db.execSQL("CREATE INDEX idx_launches_ts ON launches(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insertLaunch(row: Row) {
        writableDatabase.insert("launches", null, ContentValues().apply {
            put("pkg", row.pkg)
            put("ts", row.ts)
            put("hour", row.hour)
            put("dow", row.dow)
            put("prev_pkg", row.prevPkg)
            put("ctx_event", row.ctxEvent)
        })
    }

    fun rowsSince(sinceTs: Long): List<Row> {
        val rows = ArrayList<Row>(1024)
        readableDatabase.rawQuery(
            "SELECT pkg, ts, hour, dow, prev_pkg, ctx_event FROM launches WHERE ts >= ? ORDER BY ts",
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

    /** Number of launches logged since [sinceTs]. */
    fun countSince(sinceTs: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM launches WHERE ts >= ?", arrayOf(sinceTs.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** Caps the table so it can never grow unbounded. */
    fun prune() {
        writableDatabase.execSQL(
            """DELETE FROM launches WHERE id NOT IN
               (SELECT id FROM launches ORDER BY ts DESC LIMIT $MAX_ROWS)"""
        )
    }

    fun clearAll() {
        writableDatabase.delete("launches", null, null)
    }

    private companion object {
        const val MAX_ROWS = 5000
    }
}
