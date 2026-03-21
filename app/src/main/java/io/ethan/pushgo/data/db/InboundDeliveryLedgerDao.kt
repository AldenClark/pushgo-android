package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InboundDeliveryLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(record: InboundDeliveryLedgerEntity): Long

    @Query("SELECT ack_state FROM inbound_delivery_ledger WHERE delivery_id = :deliveryId LIMIT 1")
    suspend fun getAckState(deliveryId: String): String?

    @Query(
        """
        UPDATE inbound_delivery_ledger
        SET ack_state = :ackState, acked_at = :ackedAt
        WHERE delivery_id IN (:deliveryIds)
        """
    )
    suspend fun updateAckState(
        deliveryIds: List<String>,
        ackState: String,
        ackedAt: Long?,
    )

    @Query("DELETE FROM inbound_delivery_ledger")
    suspend fun deleteAll()
}
