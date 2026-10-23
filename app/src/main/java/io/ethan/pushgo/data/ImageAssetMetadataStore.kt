package io.ethan.pushgo.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.ethan.pushgo.util.UrlValidators
import java.security.MessageDigest

class ImageAssetMetadataStore private constructor(context: Context) {
    data class Metadata(
        val url: String,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val aspectRatio: Double,
        val mimeType: String?,
        val isAnimated: Boolean,
        val frameCount: Int?,
        val byteSize: Long?,
        val etag: String?,
        val lastModified: String?,
        val updatedAtEpochMillis: Long,
    )

    private val helper = Helper(context.applicationContext)

    fun upsert(metadata: Metadata) {
        if (metadata.pixelWidth <= 0 || metadata.pixelHeight <= 0 || metadata.aspectRatio <= 0.0) {
            return
        }
        val normalized = UrlValidators.normalizeHttpsUrl(metadata.url) ?: return
        val values = ContentValues().apply {
            put("url_hash", urlHash(normalized))
            put("url", normalized)
            put("pixel_width", metadata.pixelWidth)
            put("pixel_height", metadata.pixelHeight)
            put("aspect_ratio", metadata.aspectRatio)
            put("mime_type", metadata.mimeType)
            put("is_animated", if (metadata.isAnimated) 1 else 0)
            put("frame_count", metadata.frameCount)
            put("byte_size", metadata.byteSize)
            put("etag", metadata.etag)
            put("last_modified", metadata.lastModified)
            put("updated_at", metadata.updatedAtEpochMillis)
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun findByUrl(url: String): Metadata? {
        val normalized = UrlValidators.normalizeHttpsUrl(url) ?: return null
        val db = helper.readableDatabase
        db.query(
            TABLE_NAME,
            COLUMNS,
            "url_hash = ?",
            arrayOf(urlHash(normalized)),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val width = cursor.getInt(cursor.getColumnIndexOrThrow("pixel_width"))
            val height = cursor.getInt(cursor.getColumnIndexOrThrow("pixel_height"))
            val ratio = cursor.getDouble(cursor.getColumnIndexOrThrow("aspect_ratio"))
            return Metadata(
                url = cursor.getString(cursor.getColumnIndexOrThrow("url")),
                pixelWidth = width,
                pixelHeight = height,
                aspectRatio = ratio,
                mimeType = cursor.getString(cursor.getColumnIndexOrThrow("mime_type")),
                isAnimated = cursor.getInt(cursor.getColumnIndexOrThrow("is_animated")) != 0,
                frameCount = if (cursor.isNull(cursor.getColumnIndexOrThrow("frame_count"))) {
                    null
                } else {
                    cursor.getInt(cursor.getColumnIndexOrThrow("frame_count"))
                },
                byteSize = if (cursor.isNull(cursor.getColumnIndexOrThrow("byte_size"))) {
                    null
                } else {
                    cursor.getLong(cursor.getColumnIndexOrThrow("byte_size"))
                },
                etag = cursor.getString(cursor.getColumnIndexOrThrow("etag")),
                lastModified = cursor.getString(cursor.getColumnIndexOrThrow("last_modified")),
                updatedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")),
            )
        }
    }

    internal data class Representation(val key: String, val hash: String, val headersJson: String, val requestAt: Long, val responseAt: Long)

    internal fun representation(key: String): Representation? = helper.readableDatabase.rawQuery(
        "SELECT content_hash, headers_json, request_at, response_at FROM image_http_representations WHERE request_key=?", arrayOf(key)
    ).use { cursor -> if (!cursor.moveToFirst()) null else Representation(key, cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3)) }

    internal fun putRepresentation(value: Representation) {
        helper.writableDatabase.insertWithOnConflict("image_http_representations", null, ContentValues().apply {
            put("request_key", value.key); put("content_hash", value.hash); put("headers_json", value.headersJson)
            put("request_at", value.requestAt); put("response_at", value.responseAt)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    internal fun forgetRepresentation(key: String) { helper.writableDatabase.delete("image_http_representations", "request_key=?", arrayOf(key)) }
    internal fun forgetHash(hash: String) { helper.writableDatabase.delete("image_http_representations", "content_hash=?", arrayOf(hash)) }

    internal fun touchThumbnail(hash: String, bytes: Long, at: Long) {
        helper.writableDatabase.insertWithOnConflict("image_thumbnails", null, ContentValues().apply { put("content_hash", hash); put("byte_size", bytes); put("accessed_at", at) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    internal fun thumbnailBytes(): Long = helper.readableDatabase.rawQuery("SELECT COALESCE(SUM(byte_size),0) FROM image_thumbnails", emptyArray()).use { it.moveToFirst(); it.getLong(0) }
    internal fun oldestThumbnails(limit: Int): List<String> = helper.readableDatabase.rawQuery("SELECT content_hash FROM image_thumbnails ORDER BY accessed_at LIMIT ?", arrayOf(limit.coerceIn(1,64).toString())).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    internal fun forgetThumbnail(hash: String) { helper.writableDatabase.delete("image_thumbnails", "content_hash=?", arrayOf(hash)) }

    private fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(
        context,
        context.getDatabasePath(DATABASE_NAME).absolutePath,
        null,
        DATABASE_VERSION,
    ) {
        private fun createRepresentations(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS image_http_representations (request_key TEXT PRIMARY KEY NOT NULL, content_hash TEXT NOT NULL, headers_json TEXT NOT NULL, request_at INTEGER NOT NULL, response_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS image_http_representations_hash ON image_http_representations(content_hash)")
            db.execSQL("CREATE TABLE IF NOT EXISTS image_thumbnails (content_hash TEXT PRIMARY KEY NOT NULL, byte_size INTEGER NOT NULL, accessed_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS image_thumbnails_accessed ON image_thumbnails(accessed_at)")
        }

        override fun onCreate(db: SQLiteDatabase) {
            createRepresentations(db)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                    url_hash TEXT PRIMARY KEY NOT NULL,
                    url TEXT NOT NULL,
                    pixel_width INTEGER NOT NULL,
                    pixel_height INTEGER NOT NULL,
                    aspect_ratio REAL NOT NULL,
                    mime_type TEXT,
                    is_animated INTEGER NOT NULL,
                    frame_count INTEGER,
                    byte_size INTEGER,
                    etag TEXT,
                    last_modified TEXT,
                    updated_at INTEGER NOT NULL
                );
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_image_asset_metadata_updated_at
                ON $TABLE_NAME(updated_at DESC);
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 3) createRepresentations(db)
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
                onCreate(db)
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "pushgo_image_asset_metadata.db"
        private const val DATABASE_VERSION = 3
        private const val TABLE_NAME = "image_asset_metadata"
        private val COLUMNS = arrayOf(
            "url",
            "pixel_width",
            "pixel_height",
            "aspect_ratio",
            "mime_type",
            "is_animated",
            "frame_count",
            "byte_size",
            "etag",
            "last_modified",
            "updated_at",
        )

        private val instances = mutableMapOf<String, ImageAssetMetadataStore>()

        fun get(context: Context): ImageAssetMetadataStore = synchronized(instances) {
            val path = context.getDatabasePath(DATABASE_NAME).absolutePath
            instances.getOrPut(path) { ImageAssetMetadataStore(context) }
        }

        internal fun closeForTest(context: Context) = synchronized(instances) {
            instances.remove(context.getDatabasePath(DATABASE_NAME).absolutePath)?.helper?.close()
        }
    }
}
