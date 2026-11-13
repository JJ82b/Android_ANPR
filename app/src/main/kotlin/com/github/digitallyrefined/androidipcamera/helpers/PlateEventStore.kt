package com.github.digitallyrefined.androidipcamera.helpers

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class PlateEvent(
    val id: Long,
    val plateCode: String,
    val plateType: Int,
    val confidence: Float,
    val eventTimeMs: Long,
    val lastSeenMs: Long,
    val videoFile: String?,
    val videoOffsetMs: Long,
    val plateImgName: String?,
    val snapImgName: String?,
    val boxX1: Float = 0f,
    val boxY1: Float = 0f,
    val boxX2: Float = 0f,
    val boxY2: Float = 0f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("plateCode", plateCode)
        put("plateType", plateType)
        put("confidence", confidence.toDouble())
        put("eventTimeMs", eventTimeMs)
        put("lastSeenMs", lastSeenMs)
        put("videoFile", videoFile ?: JSONObject.NULL)
        put("videoOffsetMs", videoOffsetMs)
        put("plateImg", plateImgName ?: JSONObject.NULL)
        put("snapImg", snapImgName ?: JSONObject.NULL)
        put("box", JSONObject().apply {
            put("x1", boxX1.toDouble())
            put("y1", boxY1.toDouble())
            put("x2", boxX2.toDouble())
            put("y2", boxY2.toDouble())
        })
    }
}

/**
 * 过车车牌事件持久化存储（SQLite + 车牌/全景抓拍 JPEG）。
 * - 对同一车牌在 [DEDUP_WINDOW_MS] 内的连续识别做聚合去重，保留最高置信度帧；
 * - 记录关联的录像分段文件名与毫秒偏移量，供第 4 步回放跳转使用。
 */
class PlateEventStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val appContext = context.applicationContext
    private val snapDir = File(appContext.filesDir, "alpr_snaps").apply { mkdirs() }
    private val dbLock = Any()

    companion object {
        private const val TAG = "PlateEventStore"
        private const val DB_NAME = "anpr_events.db"
        private const val DB_VERSION = 2
        private const val TABLE_EVENTS = "plate_events"
        private const val DEDUP_WINDOW_MS = 15_000L
        private const val MAX_EVENTS_KEEP = 5000

        @Volatile
        private var instance: PlateEventStore? = null

        fun getInstance(context: Context): PlateEventStore =
            instance ?: synchronized(this) {
                instance ?: PlateEventStore(context).also { instance = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                plate_code TEXT NOT NULL,
                plate_type INTEGER NOT NULL,
                confidence REAL NOT NULL,
                event_time_ms INTEGER NOT NULL,
                last_seen_ms INTEGER NOT NULL,
                video_file TEXT,
                video_offset_ms INTEGER NOT NULL DEFAULT 0,
                plate_img TEXT,
                snap_img TEXT,
                box_x1 REAL NOT NULL DEFAULT 0,
                box_y1 REAL NOT NULL DEFAULT 0,
                box_x2 REAL NOT NULL DEFAULT 0,
                box_y2 REAL NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plate_events_time ON $TABLE_EVENTS(event_time_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_plate_events_video ON $TABLE_EVENTS(video_file)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN box_x1 REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN box_y1 REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN box_x2 REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE $TABLE_EVENTS ADD COLUMN box_y2 REAL NOT NULL DEFAULT 0")
            } catch (e: Exception) {
                Log.w(TAG, "onUpgrade v2 failed: ${e.message}")
            }
        }
    }

    /**
     * 记录一次识别结果：
     * 1) 在 15s 窗口内存在相同或仅 1 字符差异的活跃事件时，聚合为同一次过车；
     * 2) 新置信度更高时刷新车牌文本、置信度及抓拍图；
     * 3) 否则插入一条新的过车事件并裁剪保存车牌特写图与全景图。
     */
    fun recordDetection(
        plate: DetectedPlate,
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        videoFile: String?,
        videoStartMs: Long
    ) {
        val now = System.currentTimeMillis()
        val offsetMs = if (!videoFile.isNullOrBlank() && videoStartMs > 0L) {
            (now - videoStartMs).coerceAtLeast(0L)
        } else 0L
        val rot = ((rotationDegrees % 360) + 360) % 360
        val uprightW = (if (rot == 90 || rot == 270) height else width).coerceAtLeast(1)
        val uprightH = (if (rot == 90 || rot == 270) width else height).coerceAtLeast(1)
        val nx1 = (plate.x1 / uprightW).coerceIn(0f, 1f)
        val ny1 = (plate.y1 / uprightH).coerceIn(0f, 1f)
        val nx2 = (plate.x2 / uprightW).coerceIn(0f, 1f)
        val ny2 = (plate.y2 / uprightH).coerceIn(0f, 1f)

        var needImages = false
        var matchedId: Long? = null
        synchronized(dbLock) {
            try {
                val db = writableDatabase
                val recent = findRecentMatchingEvent(db, plate.code, now - DEDUP_WINDOW_MS)
                if (recent != null && plate.confidence <= recent.confidence) {
                    val values = ContentValues().apply {
                        put("last_seen_ms", now)
                        if (recent.videoFile.isNullOrBlank() && !videoFile.isNullOrBlank()) {
                            put("video_file", videoFile)
                            put("video_offset_ms", offsetMs)
                        }
                    }
                    db.update(TABLE_EVENTS, values, "id = ?", arrayOf(recent.id.toString()))
                    return
                }
                needImages = true
                matchedId = recent?.id
            } catch (e: Exception) {
                Log.e(TAG, "recordDetection pre-check failed: ${e.message}")
                return
            }
        }

        val (plateImg, snapImg) = if (needImages) {
            saveEventImages(plate, nv21, width, height, rotationDegrees, now)
        } else (null to null)

        synchronized(dbLock) {
            try {
                val db = writableDatabase
                val existing = matchedId?.let { id ->
                    findRecentMatchingEvent(db, plate.code, now - DEDUP_WINDOW_MS)?.takeIf { it.id == id }
                } ?: findRecentMatchingEvent(db, plate.code, now - DEDUP_WINDOW_MS)

                if (existing != null) {
                    val values = ContentValues().apply {
                        put("last_seen_ms", now)
                        if (existing.videoFile.isNullOrBlank() && !videoFile.isNullOrBlank()) {
                            put("video_file", videoFile)
                            put("video_offset_ms", offsetMs)
                        }
                    }
                    if (plate.confidence > existing.confidence) {
                        values.put("plate_code", plate.code)
                        values.put("plate_type", plate.type)
                        values.put("confidence", plate.confidence.toDouble())
                        values.put("box_x1", nx1.toDouble())
                        values.put("box_y1", ny1.toDouble())
                        values.put("box_x2", nx2.toDouble())
                        values.put("box_y2", ny2.toDouble())
                        if (plateImg != null) {
                            deleteSnapFile(existing.plateImgName)
                            values.put("plate_img", plateImg)
                        }
                        if (snapImg != null) {
                            deleteSnapFile(existing.snapImgName)
                            values.put("snap_img", snapImg)
                        }
                    } else {
                        deleteSnapFile(plateImg)
                        deleteSnapFile(snapImg)
                    }
                    db.update(TABLE_EVENTS, values, "id = ?", arrayOf(existing.id.toString()))
                } else {
                    val values = ContentValues().apply {
                        put("plate_code", plate.code)
                        put("plate_type", plate.type)
                        put("confidence", plate.confidence.toDouble())
                        put("event_time_ms", now)
                        put("last_seen_ms", now)
                        if (!videoFile.isNullOrBlank()) put("video_file", videoFile)
                        put("video_offset_ms", offsetMs)
                        if (plateImg != null) put("plate_img", plateImg)
                        if (snapImg != null) put("snap_img", snapImg)
                        put("box_x1", nx1.toDouble())
                        put("box_y1", ny1.toDouble())
                        put("box_x2", nx2.toDouble())
                        put("box_y2", ny2.toDouble())
                    }
                    db.insert(TABLE_EVENTS, null, values)
                    pruneExcessEventsLocked(db)
                }
            } catch (e: Exception) {
                Log.e(TAG, "recordDetection failed: ${e.message}")
            }
        }
    }

    fun queryEvents(
        query: String? = null,
        plateType: Int? = null,
        limit: Int = 50,
        offset: Int = 0
    ): List<PlateEvent> = synchronized(dbLock) {
        val whereParts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (!query.isNullOrBlank()) {
            whereParts.add("plate_code LIKE ?")
            args.add("%${query.trim()}%")
        }
        if (plateType != null && plateType >= 0) {
            whereParts.add("plate_type = ?")
            args.add(plateType.toString())
        }
        val whereClause = if (whereParts.isEmpty()) null else whereParts.joinToString(" AND ")
        val list = mutableListOf<PlateEvent>()
        readableDatabase.query(
            TABLE_EVENTS,
            null,
            whereClause,
            args.toTypedArray(),
            null,
            null,
            "event_time_ms DESC",
            "${offset.coerceAtLeast(0)},${limit.coerceIn(1, 200)}"
        ).use { c ->
            while (c.moveToNext()) {
                list.add(readCursorRow(c))
            }
        }
        list
    }

    fun queryEventsJson(query: String? = null, plateType: Int? = null, limit: Int = 50, offset: Int = 0): String {
        val items = queryEvents(query, plateType, limit, offset)
        return JSONObject().apply {
            put("events", JSONArray().apply {
                items.forEach { put(it.toJson()) }
            })
        }.toString()
    }

    fun getImageFile(name: String?): File? {
        if (name.isNullOrBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) {
            return null
        }
        val f = File(snapDir, name)
        return if (f.exists() && f.isFile) f else null
    }

    fun deleteEventsForVideo(videoFile: String) {
        if (videoFile.isBlank()) return
        synchronized(dbLock) {
            try {
                val db = writableDatabase
                db.query(
                    TABLE_EVENTS,
                    arrayOf("plate_img", "snap_img"),
                    "video_file = ?",
                    arrayOf(videoFile),
                    null,
                    null,
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        deleteSnapFile(c.getString(0))
                        deleteSnapFile(c.getString(1))
                    }
                }
                val deleted = db.delete(TABLE_EVENTS, "video_file = ?", arrayOf(videoFile))
                if (deleted > 0) {
                    Log.i(TAG, "Cleaned $deleted events for deleted video $videoFile")
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteEventsForVideo failed: ${e.message}")
            }
        }
    }

    private fun findRecentMatchingEvent(db: SQLiteDatabase, plateCode: String, minLastSeenMs: Long): PlateEvent? {
        db.query(
            TABLE_EVENTS,
            null,
            "last_seen_ms >= ?",
            arrayOf(minLastSeenMs.toString()),
            null,
            null,
            "last_seen_ms DESC",
            "15"
        ).use { c ->
            while (c.moveToNext()) {
                val row = readCursorRow(c)
                if (isSamePlate(row.plateCode, plateCode)) return row
            }
        }
        return null
    }

    private fun isSamePlate(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length != b.length || a.length < 6) return false
        var diff = 0
        for (i in a.indices) {
            if (a[i] != b[i] && ++diff > 1) return false
        }
        return diff <= 1
    }

    private fun readCursorRow(c: android.database.Cursor): PlateEvent {
        fun optFloat(col: String): Float {
            val idx = c.getColumnIndex(col)
            return if (idx >= 0 && !c.isNull(idx)) c.getFloat(idx) else 0f
        }
        return PlateEvent(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            plateCode = c.getString(c.getColumnIndexOrThrow("plate_code")),
            plateType = c.getInt(c.getColumnIndexOrThrow("plate_type")),
            confidence = c.getFloat(c.getColumnIndexOrThrow("confidence")),
            eventTimeMs = c.getLong(c.getColumnIndexOrThrow("event_time_ms")),
            lastSeenMs = c.getLong(c.getColumnIndexOrThrow("last_seen_ms")),
            videoFile = c.getString(c.getColumnIndexOrThrow("video_file")),
            videoOffsetMs = c.getLong(c.getColumnIndexOrThrow("video_offset_ms")),
            plateImgName = c.getString(c.getColumnIndexOrThrow("plate_img")),
            snapImgName = c.getString(c.getColumnIndexOrThrow("snap_img")),
            boxX1 = optFloat("box_x1"),
            boxY1 = optFloat("box_y1"),
            boxX2 = optFloat("box_x2"),
            boxY2 = optFloat("box_y2")
        )
    }

    private fun saveEventImages(
        plate: DetectedPlate,
        nv21: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        tsMs: Long
    ): Pair<String?, String?> {
        var rawBmp: Bitmap? = null
        var uprightBmp: Bitmap? = null
        try {
            val jpegStream = ByteArrayOutputStream()
            YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 90, jpegStream)
            val jpegBytes = jpegStream.toByteArray()
            rawBmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null to null

            uprightBmp = if (rotationDegrees % 360 != 0) {
                val m = Matrix().apply { postRotate((rotationDegrees % 360).toFloat()) }
                Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, m, true)
            } else {
                rawBmp
            }

            val imgW = uprightBmp.width
            val imgH = uprightBmp.height
            val padX = ((plate.x2 - plate.x1) * 0.15f).toInt().coerceAtLeast(8)
            val padY = ((plate.y2 - plate.y1) * 0.25f).toInt().coerceAtLeast(6)
            val left = (plate.x1.toInt() - padX).coerceIn(0, (imgW - 2).coerceAtLeast(0))
            val top = (plate.y1.toInt() - padY).coerceIn(0, (imgH - 2).coerceAtLeast(0))
            val right = (plate.x2.toInt() + padX).coerceIn(left + 1, imgW)
            val bottom = (plate.y2.toInt() + padY).coerceIn(top + 1, imgH)

            val unique = "${tsMs}_${System.nanoTime() % 10000}"
            val plateName = "plate_$unique.jpg"
            val snapName = "snap_$unique.jpg"

            val cropBmp = Bitmap.createBitmap(uprightBmp, left, top, right - left, bottom - top)
            try {
                FileOutputStream(File(snapDir, plateName)).use { out ->
                    cropBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } finally {
                if (cropBmp !== uprightBmp) cropBmp.recycle()
            }

            val maxSnapW = 960
            val snapBmp = if (imgW > maxSnapW) {
                val scaleH = (imgH * (maxSnapW.toFloat() / imgW)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(uprightBmp, maxSnapW, scaleH, true)
            } else {
                uprightBmp
            }
            try {
                FileOutputStream(File(snapDir, snapName)).use { out ->
                    snapBmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
                }
            } finally {
                if (snapBmp !== uprightBmp) snapBmp.recycle()
            }

            return plateName to snapName
        } catch (e: Exception) {
            Log.w(TAG, "saveEventImages failed: ${e.message}")
            return null to null
        } finally {
            if (uprightBmp != null && uprightBmp !== rawBmp) uprightBmp.recycle()
            rawBmp?.recycle()
        }
    }

    private fun deleteSnapFile(name: String?) {
        if (name.isNullOrBlank()) return
        try { File(snapDir, name).delete() } catch (_: Exception) {}
    }

    private fun pruneExcessEventsLocked(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null).use { c ->
            if (!c.moveToFirst()) return
            val count = c.getInt(0)
            if (count <= MAX_EVENTS_KEEP) return
            val toDelete = count - MAX_EVENTS_KEEP
            db.query(
                TABLE_EVENTS,
                arrayOf("id", "plate_img", "snap_img"),
                null,
                null,
                null,
                null,
                "event_time_ms ASC",
                toDelete.toString()
            ).use { old ->
                while (old.moveToNext()) {
                    val id = old.getLong(0)
                    deleteSnapFile(old.getString(1))
                    deleteSnapFile(old.getString(2))
                    db.delete(TABLE_EVENTS, "id = ?", arrayOf(id.toString()))
                }
            }
        }
    }
}
