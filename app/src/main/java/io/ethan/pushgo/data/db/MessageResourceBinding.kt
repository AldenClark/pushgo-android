package io.ethan.pushgo.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Business references live beside canonical content, not in the disposable URL lookup cache. */
@Entity(tableName = "message_resource_bindings", primaryKeys = ["owner_id", "owner_version", "request_key"], indices = [Index(value = ["content_hash"])])
data class MessageResourceBinding(
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "owner_version") val ownerVersion: String,
    @ColumnInfo(name = "request_key") val requestKey: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "bound_at") val boundAt: Long,
)

@Entity(tableName = "message_resource_owners", primaryKeys = ["source_table", "owner_id", "owner_slot"], indices = [Index(value = ["generation"], unique = true), Index(value = ["owner_id"])])
data class MessageResourceOwner(
    @ColumnInfo(name = "source_table") val sourceTable: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    @ColumnInfo(name = "owner_slot") val ownerSlot: Int,
    val generation: String,
    @ColumnInfo(name = "origin_scope") val originScope: String?,
    @ColumnInfo(name = "authenticated_gateway_url") val authenticatedGatewayUrl: String? = null,
    @ColumnInfo(name = "authenticated_device_key") val authenticatedDeviceKey: String? = null,
)

@Dao
interface MessageResourceDao {
    @Query("SELECT * FROM message_resource_bindings WHERE owner_id=:ownerId AND owner_version=:ownerVersion AND request_key=:requestKey LIMIT 1")
    suspend fun find(ownerId: String, ownerVersion: String, requestKey: String): MessageResourceBinding?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun bind(binding: MessageResourceBinding): Long

    @Query("SELECT EXISTS(SELECT 1 FROM message_resource_bindings WHERE content_hash=:hash)")
    suspend fun isReferenced(hash: String): Boolean

    @Query("SELECT m.server_id AS serverId,m.received_at AS receivedAt,m.raw_payload_json AS rawPayloadJson,o.generation,o.origin_scope AS originScope,o.source_table AS sourceTable,o.owner_slot AS ownerSlot FROM messages m JOIN message_resource_owners o ON o.source_table='messages' AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.id=:id UNION ALL SELECT m.server_id AS serverId,m.received_at AS receivedAt,m.raw_payload_json AS rawPayloadJson,o.generation,o.origin_scope AS originScope,o.source_table AS sourceTable,o.owner_slot AS ownerSlot FROM thing_sub_messages m JOIN message_resource_owners o ON o.source_table='thing_sub_messages' AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.id=:id UNION ALL SELECT m.server_id AS serverId,m.received_at AS receivedAt,m.raw_payload_json AS rawPayloadJson,o.generation,o.origin_scope AS originScope,o.source_table AS sourceTable,o.owner_slot AS ownerSlot FROM event_change_logs m JOIN message_resource_owners o ON o.source_table='event_change_logs' AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.id=:id UNION ALL SELECT m.server_id AS serverId,m.received_at AS receivedAt,m.raw_payload_json AS rawPayloadJson,o.generation,o.origin_scope AS originScope,o.source_table AS sourceTable,o.owner_slot AS ownerSlot FROM thing_change_logs m JOIN message_resource_owners o ON o.source_table='thing_change_logs' AND o.owner_id=m.id AND o.owner_slot=0 WHERE m.id=:id UNION ALL SELECT m.server_id AS serverId,m.received_at AS receivedAt,m.raw_payload_json AS rawPayloadJson,o.generation,o.origin_scope AS originScope,o.source_table AS sourceTable,o.owner_slot AS ownerSlot FROM thing_sub_events m JOIN message_resource_owners o ON o.source_table='thing_sub_events' AND o.owner_id=m.id AND o.owner_slot=m.activated WHERE m.id=:id")
    suspend fun owners(id: String): List<ResourceOwnerRow>

    @Query("UPDATE message_resource_owners SET origin_scope=:scope,authenticated_gateway_url=:gatewayUrl,authenticated_device_key=:deviceKey WHERE owner_id=:id AND generation=:generation AND (origin_scope IS NULL OR origin_scope=:scope) AND (authenticated_gateway_url IS NULL OR authenticated_gateway_url=:gatewayUrl) AND (authenticated_device_key IS NULL OR authenticated_device_key=:deviceKey)")
    suspend fun setOrigin(id: String, generation: String, scope: String, gatewayUrl: String, deviceKey: String)

}

data class ResourceOwnerRow(val serverId: String?, val receivedAt: Long, val rawPayloadJson: String, val generation: String, val originScope: String?, val sourceTable: String, val ownerSlot: Int)
