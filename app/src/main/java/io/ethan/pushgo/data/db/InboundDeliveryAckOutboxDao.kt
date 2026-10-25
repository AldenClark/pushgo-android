package io.ethan.pushgo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

data class InboundDeliveryAckDestinationRow(
    val gatewayUrl: String,
    val deviceKey: String,
    val ackContract: String,
    val oldestUpdatedAt: Long,
)

@Dao
interface InboundDeliveryAckOutboxDao {
    @Query("SELECT EXISTS(SELECT 1 FROM inbound_delivery_ack_outbox LIMIT 1)")
    suspend fun hasPending(): Boolean

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM inbound_delivery_ack_outbox AS outbox
            JOIN inbound_delivery_ledger AS ledger
              ON ledger.gateway_url = outbox.gateway_url
             AND ledger.device_key = outbox.device_key
             AND ledger.delivery_id = outbox.delivery_id
            WHERE ledger.acceptance_proven = 1 LIMIT 1
        )
        """
    )
    suspend fun hasEligiblePending(): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(record: InboundDeliveryAckOutboxEntity): Long

    @Query(
        """
        UPDATE inbound_delivery_ack_outbox
        SET ack_contract = :ackContract,
            source = :source,
            updated_at = :updatedAt
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
        """
    )
    suspend fun refreshPreservingAttempts(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
        ackContract: String,
        source: String,
        updatedAt: Long,
    ): Int

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
        SELECT gateway_url AS gatewayUrl,
               device_key AS deviceKey,
               ack_contract AS ackContract,
               MIN(updated_at) AS oldestUpdatedAt
        FROM inbound_delivery_ack_outbox
        GROUP BY gateway_url, device_key, ack_contract
        ORDER BY oldestUpdatedAt ASC, gateway_url ASC, device_key ASC, ack_contract ASC
        LIMIT :limit
        """
    )
    suspend fun loadPendingDestinations(limit: Int): List<InboundDeliveryAckDestinationRow>

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

    /** Raw loads above retain old unresolved facts for diagnostics, never wire selection. */
    @Query(
        """
        SELECT outbox.* FROM inbound_delivery_ack_outbox AS outbox
        JOIN inbound_delivery_ledger AS ledger
          ON ledger.gateway_url = outbox.gateway_url
         AND ledger.device_key = outbox.device_key
         AND ledger.delivery_id = outbox.delivery_id
        WHERE ledger.acceptance_proven = 1
        ORDER BY outbox.updated_at ASC, outbox.enqueued_at ASC
        LIMIT :limit
        """
    )
    suspend fun loadEligiblePending(limit: Int): List<InboundDeliveryAckOutboxEntity>

    @Query(
        """
        SELECT outbox.gateway_url AS gatewayUrl,
               outbox.device_key AS deviceKey,
               outbox.ack_contract AS ackContract,
               MIN(outbox.updated_at) AS oldestUpdatedAt
        FROM inbound_delivery_ack_outbox AS outbox
        JOIN inbound_delivery_ledger AS ledger
          ON ledger.gateway_url = outbox.gateway_url
         AND ledger.device_key = outbox.device_key
         AND ledger.delivery_id = outbox.delivery_id
        WHERE ledger.acceptance_proven = 1
        GROUP BY outbox.gateway_url, outbox.device_key, outbox.ack_contract
        ORDER BY oldestUpdatedAt ASC, outbox.gateway_url ASC, outbox.device_key ASC, outbox.ack_contract ASC
        LIMIT :limit
        """
    )
    suspend fun loadEligiblePendingDestinations(limit: Int): List<InboundDeliveryAckDestinationRow>

    @Query(
        """
        SELECT outbox.* FROM inbound_delivery_ack_outbox AS outbox
        JOIN inbound_delivery_ledger AS ledger
          ON ledger.gateway_url = outbox.gateway_url
         AND ledger.device_key = outbox.device_key
         AND ledger.delivery_id = outbox.delivery_id
        WHERE ledger.acceptance_proven = 1
          AND outbox.gateway_url = :gatewayUrl
          AND outbox.device_key = :deviceKey
          AND outbox.ack_contract = :ackContract
        ORDER BY outbox.updated_at ASC, outbox.enqueued_at ASC
        LIMIT :limit
        """
    )
    suspend fun loadEligiblePendingForDestination(
        gatewayUrl: String,
        deviceKey: String,
        ackContract: String,
        limit: Int,
    ): List<InboundDeliveryAckOutboxEntity>

    @Query(
        """
        UPDATE inbound_delivery_ack_outbox
        SET updated_at = :attemptStartedAt,
            attempt_count = attempt_count + 1,
            last_attempt_uncertain = 1
        WHERE gateway_url = :gatewayUrl
          AND device_key = :deviceKey
          AND delivery_id = :deliveryId
          AND updated_at = :expectedUpdatedAt
          AND EXISTS (
              SELECT 1 FROM inbound_delivery_ledger AS ledger
              WHERE ledger.gateway_url = inbound_delivery_ack_outbox.gateway_url
                AND ledger.device_key = inbound_delivery_ack_outbox.device_key
                AND ledger.delivery_id = inbound_delivery_ack_outbox.delivery_id
                AND ledger.acceptance_proven = 1
          )
        """
    )
    suspend fun beginAttemptIfUnchanged(
        gatewayUrl: String,
        deviceKey: String,
        deliveryId: String,
        expectedUpdatedAt: Long,
        attemptStartedAt: Long,
    ): Int

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
            last_attempt_uncertain = :lastAttemptUncertain
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
        lastAttemptUncertain: Boolean,
    ): Int

    @Query("DELETE FROM inbound_delivery_ack_outbox")
    suspend fun deleteAll()
}
