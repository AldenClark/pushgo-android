package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InboundDeliveryAckOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<InboundDeliveryAckOutboxEntity>)

    @Query(
        """
        SELECT delivery_id
        FROM inbound_delivery_ack_outbox
        ORDER BY enqueued_at ASC, updated_at ASC
        LIMIT :limit
        """
    )
    suspend fun loadPendingDeliveryIds(limit: Int): List<String>

    @Query("DELETE FROM inbound_delivery_ack_outbox WHERE delivery_id IN (:deliveryIds)")
    suspend fun deleteByDeliveryIds(deliveryIds: List<String>)

    @Query("DELETE FROM inbound_delivery_ack_outbox")
    suspend fun deleteAll()
}
