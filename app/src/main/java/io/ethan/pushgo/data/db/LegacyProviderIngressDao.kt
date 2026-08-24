package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LegacyProviderIngressDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: Collection<LegacyProviderIngressEntity>)

    @Query(
        """
        SELECT * FROM legacy_provider_ingress
        ORDER BY enqueued_at ASC, delivery_id ASC
        LIMIT :limit
        """
    )
    suspend fun loadPending(limit: Int): List<LegacyProviderIngressEntity>

    @Query(
        """
        DELETE FROM legacy_provider_ingress
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
        """
    )
    suspend fun delete(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
    ): Int

    @Query("DELETE FROM legacy_provider_ingress")
    suspend fun deleteAll()
}
