package net.pocketmap

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Standard MBTiles, TMS rows, and FIFO eviction. Seed archives are never written. */
class MbTilesCache(file: File, private val highWater: Long = 500L * 1024 * 1024, private val lowWater: Long = 400L * 1024 * 1024) : Closeable {
    private val db: SQLiteDatabase
    private var writes = 0
    init {
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, tile_row INTEGER NOT NULL, tile_data BLOB NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level,tile_column,tile_row)")
        db.execSQL("CREATE TABLE IF NOT EXISTS trail_cache (sequence INTEGER PRIMARY KEY AUTOINCREMENT, zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, tile_row INTEGER NOT NULL, UNIQUE(zoom_level,tile_column,tile_row))")
    }
    @Synchronized fun get(z: Int, x: Int, y: Int): ByteArray? = read(db, z, x, y)
    @Synchronized fun put(z: Int, x: Int, y: Int, data: ByteArray) {
        if (!db.isOpen) return
        val values = ContentValues().apply { put("zoom_level", z); put("tile_column", x); put("tile_row", xyzToTmsY(z, y.toLong())) }
        db.beginTransaction()
        try {
            db.insertWithOnConflict("trail_cache", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            values.put("tile_data", data)
            db.insertWithOnConflict("tiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (++writes % 64 == 0) trim()
    }
    private fun size(): Long {
        fun pragma(key: String) = db.rawQuery("PRAGMA $key", null).use { it.moveToFirst(); it.getLong(0) }
        return (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
    }
    private fun trim() {
        if (size() <= highWater) return
        while (size() > lowWater) {
            val victims = db.rawQuery("SELECT zoom_level,tile_column,tile_row FROM trail_cache ORDER BY sequence LIMIT 512", null).use { c ->
                buildList { while (c.moveToNext()) add(arrayOf(c.getString(0), c.getString(1), c.getString(2))) }
            }
            if (victims.isEmpty()) break
            db.beginTransaction()
            try {
                victims.forEach { args ->
                    db.delete("tiles", "zoom_level=? AND tile_column=? AND tile_row=?", args)
                    db.delete("trail_cache", "zoom_level=? AND tile_column=? AND tile_row=?", args)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
    }
    @Synchronized override fun close() { if (db.isOpen) db.close() }
    companion object {
        fun xyzToTmsY(z: Int, y: Long): Long = (1L shl z) - 1 - y
        fun read(db: SQLiteDatabase, z: Int, x: Int, y: Int): ByteArray? {
            if (!db.isOpen) return null
            return db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1", arrayOf(z.toString(), x.toString(), xyzToTmsY(z, y.toLong()).toString())).use { if (it.moveToFirst()) it.getBlob(0) else null }
        }
    }
}
