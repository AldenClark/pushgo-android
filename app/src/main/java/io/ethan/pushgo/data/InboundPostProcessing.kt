package io.ethan.pushgo.data

import androidx.room.withTransaction
import io.ethan.pushgo.data.db.PushGoDatabase
import io.ethan.pushgo.data.model.PushMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val POST_PROCESS_MEDIA = 1
internal const val POST_PROCESS_NOTIFICATION = 2
internal const val POST_PROCESS_PAGE_SIZE = 64

// These are the concrete canonical rows, not another payload/outbox copy. Their existing
// resource-owner generation also fences deletion and same-ID recreation for deferred work.
internal val POST_PROCESS_TABLES = listOf(
    "messages", "thing_sub_messages", "event_change_logs", "thing_change_logs", "thing_sub_events",
)

internal data class PostProcessLocator(
    val tableIndex: Int,
    val rowId: Long,
    val generation: String,
    val flags: Int,
)

internal data class PostProcessSnapshot(
    val locator: PostProcessLocator,
    val message: PushMessage,
    val entityType: String,
    val entityId: String,
    val eventId: String?,
    val thingId: String?,
)

/** Called inside the same transaction that accepted a new canonical row. */
internal fun PushGoDatabase.markPostProcessPending(table: String, id: String, flags: Int) {
    if (flags == 0) return
    check(inTransaction()) { "post-processing responsibility must commit with canonical content" }
    require(table in POST_PROCESS_TABLES)
    val active = if (table == "thing_sub_events") " AND activated=1" else ""
    openHelper.writableDatabase.execSQL(
        "UPDATE $table SET post_process_pending=(post_process_pending | ?) WHERE id=?$active",
        arrayOf<Any>(flags, id),
    )
}

/** At most one small indexed page; rowid is only scan progress, never business identity. */
internal suspend fun PushGoDatabase.loadPendingPostProcess(
    tableIndex: Int,
    afterFlags: Int = 0,
    afterRowId: Long = 0,
    limit: Int = POST_PROCESS_PAGE_SIZE,
): List<PostProcessLocator> = withContext(Dispatchers.IO) {
    val table = POST_PROCESS_TABLES.getOrNull(tableIndex) ?: return@withContext emptyList()
    val slot = if (table == "thing_sub_events") "t.activated" else "0"
    val active = if (table == "thing_sub_events") " AND t.activated=1" else ""
    openHelper.readableDatabase.query(
        """SELECT t.rowid,o.generation,t.post_process_pending FROM $table AS t INDEXED BY index_${table}_post_process_pending
           CROSS JOIN message_resource_owners o ON o.source_table=? AND o.owner_id=t.id AND o.owner_slot=$slot
           WHERE t.post_process_pending>0 AND (t.post_process_pending,t.rowid)>(?,?)$active
           ORDER BY t.post_process_pending,t.rowid LIMIT ?""".trimIndent(),
        arrayOf<Any>(table, afterFlags, afterRowId, limit.coerceIn(1, POST_PROCESS_PAGE_SIZE)),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(PostProcessLocator(tableIndex, cursor.getLong(0), cursor.getString(1), cursor.getInt(2)))
        }
    }
}

/** No HTTP, old notification lookup or inferred owner is involved in this read. */
internal suspend fun PushGoDatabase.postProcessSnapshot(locator: PostProcessLocator): PostProcessSnapshot? =
    withTransaction { postProcessSnapshotInTransaction(locator) }

private suspend fun PushGoDatabase.postProcessSnapshotInTransaction(locator: PostProcessLocator): PostProcessSnapshot? {
    val table = POST_PROCESS_TABLES.getOrNull(locator.tableIndex) ?: return null
    val slot = if (table == "thing_sub_events") "t.activated" else "0"
    val active = if (table == "thing_sub_events") " AND t.activated=1" else ""
    val row = openHelper.readableDatabase.query(
        """SELECT t.id,t.post_process_pending FROM $table t
           JOIN message_resource_owners o ON o.source_table=? AND o.owner_id=t.id AND o.owner_slot=$slot
           WHERE t.rowid=? AND o.generation=? AND t.post_process_pending>0$active LIMIT 1""".trimIndent(),
        arrayOf<Any>(table, locator.rowId, locator.generation),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) to cursor.getInt(1) else null } ?: return null
    val current = locator.copy(flags = row.second)
    return when (table) {
        "messages" -> messageDao().getById(row.first)?.asModel()?.let {
            PostProcessSnapshot(current, it, "message", it.id, it.eventId, it.thingId)
        }
        "thing_sub_messages" -> thingSubMessageDao().getById(row.first)?.asModel()?.let {
            PostProcessSnapshot(current, it, "message", it.id, it.eventId, it.thingId)
        }
        "event_change_logs" -> eventChangeLogDao().getById(row.first)?.let {
            PostProcessSnapshot(current, it.asModel(), "event", it.entityId, it.eventId, it.thingId)
        }
        "thing_change_logs" -> thingChangeLogDao().getById(row.first)?.let {
            PostProcessSnapshot(current, it.asModel(), "thing", it.entityId, it.eventId, it.thingId)
        }
        "thing_sub_events" -> thingSubEventDao().getById(row.first)?.let {
            PostProcessSnapshot(current, it.asModel(), "event", it.entityId, it.eventId, it.thingId)
        }
        else -> null
    }
}

/** Local synchronous publication stays in the existing short deletion fence, never over HTTP. */
internal suspend fun PushGoDatabase.finishPostProcessIfCurrent(
    locator: PostProcessLocator,
    flag: Int,
    perform: (PostProcessSnapshot) -> Boolean = { true },
): Boolean = withTransaction {
    val snapshot = postProcessSnapshotInTransaction(locator) ?: return@withTransaction true
    if (snapshot.locator.flags and flag == 0) return@withTransaction true
    if (!perform(snapshot)) return@withTransaction false
    val table = POST_PROCESS_TABLES[locator.tableIndex]
    // The transaction and exact generation check above prevent clearing a new incarnation.
    openHelper.writableDatabase.execSQL(
        "UPDATE $table SET post_process_pending=(post_process_pending & ?) WHERE rowid=?",
        arrayOf<Any>(flag.inv(), locator.rowId),
    )
    true
}

internal suspend fun PushGoDatabase.postProcessLocator(table: String, id: String, expectedOriginScope: String? = null): PostProcessLocator? {
    // A locator is one read-only hint; execution still rechecks its generation in
    // finishPostProcessIfCurrent's transaction. Retain a caller's uncommitted view
    // when already inside its transaction, otherwise use a normal IO statement.
    return if (isOpen && inTransaction()) readPostProcessLocator(table, id, expectedOriginScope)
    else withContext(Dispatchers.IO) { readPostProcessLocator(table, id, expectedOriginScope) }
}

private fun PushGoDatabase.readPostProcessLocator(table: String, id: String, expectedOriginScope: String?): PostProcessLocator? {
    val tableIndex = POST_PROCESS_TABLES.indexOf(table)
    if (tableIndex < 0) return null
    val slot = if (table == "thing_sub_events") "t.activated" else "0"
    val active = if (table == "thing_sub_events") " AND t.activated=1" else ""
    val originFilter = if (expectedOriginScope == null) "" else " AND o.origin_scope=?"
    val bindings: Array<Any> = if (expectedOriginScope == null) arrayOf(table, id) else arrayOf(table, id, expectedOriginScope)
    return openHelper.readableDatabase.query(
        """SELECT t.rowid,o.generation,t.post_process_pending FROM $table t
           JOIN message_resource_owners o ON o.source_table=? AND o.owner_id=t.id AND o.owner_slot=$slot
           WHERE t.id=? AND t.post_process_pending>0$active$originFilter LIMIT 1""".trimIndent(), bindings,
    ).use { cursor ->
        if (cursor.moveToFirst()) PostProcessLocator(tableIndex, cursor.getLong(0), cursor.getString(1), cursor.getInt(2)) else null
    }
}
