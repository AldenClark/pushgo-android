package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Query

@Dao
interface InboundDeliveryAckOutboxDao {
    @Query(
        """
        INSERT INTO inbound_delivery_ack_outbox(
            gateway_url, device_key, delivery_id, ack_contract, source,
            enqueued_at, updated_at, attempt_count
        ) VALUES(
            :gatewayUrl, :deviceKey, :deliveryId, :ackContract, :source,
            :enqueuedAt, :updatedAt, 0
        )
        ON CONFLICT(gateway_url, device_key, delivery_id) DO UPDATE SET
            ack_contract = excluded.ack_contract,
            source = excluded.source,
            updated_at = excluded.updated_at
        """
    )
    suspend fun upsertPreservingAttempts(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
        ackContract: String,
        source: String,
        enqueuedAt: Long,
        updatedAt: Long,
    )

    @Query(
        """
        SELECT *
        FROM inbound_delivery_ack_outbox
        ORDER BY updated_at ASC, enqueued_at ASC
        LIMIT :limit
        """
    )
    suspend fun loadPending(limit: Int): List<InboundDeliveryAckOutboxEntity>

    @Query(
        """
        SELECT *
        FROM inbound_delivery_ack_outbox
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND ack_contract = :ackContract
        ORDER BY updated_at ASC, enqueued_at ASC
        LIMIT :limit
        """
    )
    suspend fun loadPendingForDestination(
        gatewayUrl: String,
        deviceKey: String,
        ackContract: String,
        limit: Int,
    ): List<InboundDeliveryAckOutboxEntity>

    @Query(
        """
        DELETE FROM inbound_delivery_ack_outbox
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
          AND updated_at = :updatedAt
        """
    )
    suspend fun deleteIfUnchanged(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE inbound_delivery_ack_outbox
        SET updated_at = :nextUpdatedAt,
            attempt_count = attempt_count + 1
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
          AND updated_at = :expectedUpdatedAt
        """
    )
    suspend fun deferIfUnchanged(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
        expectedUpdatedAt: Long,
        nextUpdatedAt: Long,
    ): Int

    @Query("DELETE FROM inbound_delivery_ack_outbox")
    suspend fun deleteAll()
}
