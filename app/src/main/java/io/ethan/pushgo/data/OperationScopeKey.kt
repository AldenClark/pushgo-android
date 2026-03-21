package io.ethan.pushgo.data

fun composeOperationScopeKey(
    channelId: String?,
    entityType: String?,
    entityId: String?,
    opId: String?,
): String {
    val channelSlot = normalizeScopeSlot(channelId)
    val typeSlot = normalizeScopeSlot(entityType)
    val entitySlot = normalizeScopeSlot(entityId)
    val opSlot = normalizeScopeSlot(opId)
    return "$channelSlot|$typeSlot|$entitySlot|$opSlot"
}

private fun normalizeScopeSlot(raw: String?): String {
    val normalized = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: "_"
    return normalized.replace("|", "%7C")
}
