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

    private fun urlHash(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
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
            if (oldVersion < 2) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
                onCreate(db)
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "pushgo_image_asset_metadata.db"
        private const val DATABASE_VERSION = 2
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

        @Volatile
        private var instance: ImageAssetMetadataStore? = null

        fun get(context: Context): ImageAssetMetadataStore {
            return instance ?: synchronized(this) {
                instance ?: ImageAssetMetadataStore(context).also { instance = it }
            }
        }
    }
}
