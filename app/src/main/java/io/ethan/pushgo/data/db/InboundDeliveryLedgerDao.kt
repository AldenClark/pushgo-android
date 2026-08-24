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

    @Query(
        """
        DELETE FROM inbound_delivery_ledger
        WHERE rowid IN (
            SELECT ledger.rowid
            FROM inbound_delivery_ledger AS ledger
            WHERE ledger.ack_state = :ackedState
              AND ledger.acked_at IS NOT NULL
              AND ledger.acked_at <= :ackedBeforeOrAt
              AND NOT EXISTS (
                  SELECT 1
                  FROM inbound_delivery_ack_outbox AS outbox
                  WHERE outbox.gateway_url = ledger.gateway_url
                    AND outbox.device_key = ledger.device_key
                    AND outbox.delivery_id = ledger.delivery_id
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM legacy_provider_ingress AS staging
                  WHERE staging.gateway_url = ledger.gateway_url
                    AND staging.device_key = ledger.device_key
                    AND staging.delivery_id = ledger.delivery_id
              )
            ORDER BY ledger.acked_at ASC
            LIMIT :limit
        )
        """
    )
    suspend fun pruneTerminalTombstones(
        ackedState: String,
        ackedBeforeOrAt: Long,
        limit: Int,
    ): Int

    @Query("DELETE FROM inbound_delivery_ledger")
    suspend fun deleteAll()
}
