package com.fusionphotos.app.upload

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-backed queue.
 *
 * SQLite rather than preferences or JSON because the guarantees that matter here are the ones
 * a real database gives: an atomic claim (a conditional `UPDATE` inside a transaction, so two
 * workers cannot take the same row), and durability across a process kill.
 */
class SqliteUploadQueueStore(context: Context) : UploadQueueStore {

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_REF TEXT PRIMARY KEY,
                    $COL_MEDIA_ID INTEGER NOT NULL,
                    $COL_URI TEXT NOT NULL,
                    $COL_NAME TEXT NOT NULL,
                    $COL_MIME TEXT NOT NULL,
                    $COL_CAPTURED INTEGER NOT NULL DEFAULT 0,
                    $COL_STATE TEXT NOT NULL,
                    $COL_ATTEMPTS INTEGER NOT NULL DEFAULT 0,
                    $COL_ERROR TEXT,
                    $COL_ASSET TEXT,
                    $COL_NEXT_AT INTEGER NOT NULL DEFAULT 0,
                    $COL_UPDATED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            // The claim orders by (next_attempt_at, updated_at); index it so a long queue does
            // not scan on every claim.
            db.execSQL("CREATE INDEX idx_queue_due ON $TABLE ($COL_NEXT_AT, $COL_UPDATED)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 is the first schema. A future change must migrate rather than drop: the queue
            // is the record of what still needs uploading.
        }
    }

    override fun enqueue(items: List<QueueItem>): Int {
        if (items.isEmpty()) return 0
        val db = helper.writableDatabase
        var added = 0
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            for (item in items) {
                val row = ContentValues().apply {
                    put(COL_REF, item.clientRef)
                    put(COL_MEDIA_ID, item.mediaId)
                    put(COL_URI, item.uri)
                    put(COL_NAME, item.displayName)
                    put(COL_MIME, item.mimeType)
                    put(COL_CAPTURED, item.capturedAtMs)
                    put(COL_STATE, QueueState.PENDING.name)
                    put(COL_ATTEMPTS, 0)
                    putNull(COL_ERROR)
                    putNull(COL_ASSET)
                    put(COL_NEXT_AT, 0L)
                    put(COL_UPDATED, now)
                }
                // IGNORE, not REPLACE: an existing row keeps its state and attempt count.
                if (db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_IGNORE) != -1L) {
                    added++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    override fun claimNext(nowMs: Long): QueueItem? {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // Pending rows carry next_attempt_at = 0, so this single ordering prefers fresh
            // work first and then the retry that has been waiting longest.
            val candidate = db.rawQuery(
                """
                SELECT $COL_REF, $COL_MEDIA_ID, $COL_URI, $COL_NAME, $COL_MIME, $COL_CAPTURED,
                       $COL_STATE, $COL_ATTEMPTS, $COL_ERROR, $COL_ASSET
                FROM $TABLE
                WHERE $COL_STATE IN (?, ?) AND $COL_NEXT_AT <= ?
                ORDER BY $COL_NEXT_AT ASC, $COL_UPDATED ASC
                LIMIT 1
                """.trimIndent(),
                arrayOf(QueueState.PENDING.name, QueueState.FAILED.name, nowMs.toString()),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.toItem()
            }

            val claimed = ContentValues().apply {
                put(COL_STATE, QueueState.IN_FLIGHT.name)
                put(COL_UPDATED, nowMs)
            }
            val rows = db.update(
                TABLE,
                claimed,
                "$COL_REF = ? AND $COL_STATE IN (?, ?)",
                arrayOf(candidate.clientRef, QueueState.PENDING.name, QueueState.FAILED.name),
            )
            if (rows != 1) return null // another worker claimed it first
            db.setTransactionSuccessful()
            return candidate
        } finally {
            db.endTransaction()
        }
    }

    override fun markUploaded(clientRef: String, assetId: String, nowMs: Long) {
        val values = ContentValues().apply {
            put(COL_STATE, QueueState.UPLOADED.name)
            put(COL_ASSET, assetId)
            putNull(COL_ERROR)
            put(COL_NEXT_AT, 0L)
            put(COL_UPDATED, nowMs)
        }
        helper.writableDatabase.update(TABLE, values, "$COL_REF = ?", arrayOf(clientRef))
    }

    override fun markFailed(
        clientRef: String,
        error: String,
        attempts: Int,
        nextAttemptAtMs: Long,
        dead: Boolean,
        nowMs: Long,
    ) {
        val values = ContentValues().apply {
            put(COL_STATE, if (dead) QueueState.DEAD.name else QueueState.FAILED.name)
            put(COL_ATTEMPTS, attempts)
            put(COL_ERROR, error.take(500))
            put(COL_NEXT_AT, if (dead) 0L else nextAttemptAtMs)
            put(COL_UPDATED, nowMs)
        }
        helper.writableDatabase.update(TABLE, values, "$COL_REF = ?", arrayOf(clientRef))
    }

    override fun releaseInFlight(nowMs: Long): Int {
        val values = ContentValues().apply {
            put(COL_STATE, QueueState.PENDING.name)
            put(COL_UPDATED, nowMs)
        }
        return helper.writableDatabase.update(
            TABLE,
            values,
            "$COL_STATE = ?",
            arrayOf(QueueState.IN_FLIGHT.name),
        )
    }

    override fun counts(): QueueCounts {
        val byState = mutableMapOf<String, Int>()
        helper.readableDatabase
            .rawQuery("SELECT $COL_STATE, COUNT(*) FROM $TABLE GROUP BY $COL_STATE", null)
            .use { cursor ->
                while (cursor.moveToNext()) byState[cursor.getString(0)] = cursor.getInt(1)
            }
        return QueueCounts(
            pending = byState[QueueState.PENDING.name] ?: 0,
            inFlight = byState[QueueState.IN_FLIGHT.name] ?: 0,
            uploaded = byState[QueueState.UPLOADED.name] ?: 0,
            failed = byState[QueueState.FAILED.name] ?: 0,
            dead = byState[QueueState.DEAD.name] ?: 0,
        )
    }

    override fun items(state: QueueState, limit: Int): List<QueueItem> {
        val out = mutableListOf<QueueItem>()
        helper.readableDatabase.rawQuery(
            SELECT_COLUMNS + " WHERE $COL_STATE = ? ORDER BY $COL_UPDATED DESC LIMIT ?",
            arrayOf(state.name, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toItem()
        }
        return out
    }

    override fun retryDead(nowMs: Long): Int {
        val values = ContentValues().apply {
            put(COL_STATE, QueueState.PENDING.name)
            put(COL_ATTEMPTS, 0)
            put(COL_NEXT_AT, 0L)
            put(COL_UPDATED, nowMs)
        }
        return helper.writableDatabase.update(TABLE, values, "$COL_STATE = ?", arrayOf(QueueState.DEAD.name))
    }

    override fun clearUploaded(): Int =
        helper.writableDatabase.delete(TABLE, "$COL_STATE = ?", arrayOf(QueueState.UPLOADED.name))

    private fun android.database.Cursor.toItem() = QueueItem(
        clientRef = getString(0),
        mediaId = getLong(1),
        uri = getString(2),
        displayName = getString(3),
        mimeType = getString(4),
        capturedAtMs = getLong(5),
        state = QueueState.valueOf(getString(6)),
        attempts = getInt(7),
        lastError = if (isNull(8)) null else getString(8),
        assetId = if (isNull(9)) null else getString(9),
    )

    private companion object {
        const val DB_NAME = "fusion-upload-queue.db"
        const val DB_VERSION = 1
        const val TABLE = "upload_queue"
        const val COL_REF = "client_ref"
        const val COL_MEDIA_ID = "media_id"
        const val COL_URI = "uri"
        const val COL_NAME = "display_name"
        const val COL_MIME = "mime_type"
        const val COL_CAPTURED = "captured_at_ms"
        const val COL_STATE = "state"
        const val COL_ATTEMPTS = "attempts"
        const val COL_ERROR = "last_error"
        const val COL_ASSET = "asset_id"
        const val COL_NEXT_AT = "next_attempt_at_ms"
        const val COL_UPDATED = "updated_at_ms"

        val SELECT_COLUMNS =
            "SELECT $COL_REF, $COL_MEDIA_ID, $COL_URI, $COL_NAME, $COL_MIME, $COL_CAPTURED, " +
                "$COL_STATE, $COL_ATTEMPTS, $COL_ERROR, $COL_ASSET FROM $TABLE"
    }
}
