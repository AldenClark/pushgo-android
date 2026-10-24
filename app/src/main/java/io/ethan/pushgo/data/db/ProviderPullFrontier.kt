package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Rebuildable scan progress; canonical content and ACK ledgers retain delivery responsibility. */
@Entity(tableName = "provider_pull_frontier")
data class ProviderPullFrontierEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "scope_key") val scopeKey: String,
    @ColumnInfo(name = "request_generation") val requestGeneration: String,
    @ColumnInfo(name = "page_token") val pageToken: String?,
) {
    override fun toString(): String = "ProviderPullFrontierEntity(id=$id)"
}

@Dao
interface ProviderPullFrontierDao {
    @Query("SELECT * FROM provider_pull_frontier WHERE id = 1")
    suspend fun get(): ProviderPullFrontierEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(frontier: ProviderPullFrontierEntity)

    @Query("""
        UPDATE provider_pull_frontier
        SET page_token = :pageToken, request_generation = :newGeneration
        WHERE id = 1 AND scope_key = :expectedScope AND request_generation = :expectedGeneration
    """)
    suspend fun checkpoint(
        expectedScope: String,
        expectedGeneration: String,
        pageToken: String?,
        newGeneration: String,
    ): Int

    @Query("""
        DELETE FROM provider_pull_frontier
        WHERE id = 1 AND scope_key = :expectedScope AND request_generation = :expectedGeneration
    """)
    suspend fun clear(expectedScope: String, expectedGeneration: String): Int
}
