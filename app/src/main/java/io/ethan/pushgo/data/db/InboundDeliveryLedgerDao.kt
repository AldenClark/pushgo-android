package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InboundDeliveryLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(record: InboundDeliveryLedgerEntity): Long

    @Query(
        """
        SELECT ack_state FROM inbound_delivery_ledger
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
        LIMIT 1
        """
    )
    suspend fun getAckState(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
    ): String?

    @Query(
        """
        UPDATE inbound_delivery_ledger
        SET ack_state = :ackState, acked_at = :ackedAt
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
        """
    )
    suspend fun updateAckState(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
        ackState: String,
        ackedAt: Long?,
    ): Int

    @Query("DELETE FROM inbound_delivery_ledger")
    suspend fun deleteAll()
}
