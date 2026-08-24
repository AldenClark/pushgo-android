package io.ethan.pushgo.ui.screens

import io.ethan.pushgo.util.SearchTextNormalizer

internal fun eventMatchesSearch(event: EventCardModel, rawQuery: String): Boolean {
    return matchesNormalizedSubstring(
        rawQuery = rawQuery,
        candidates = buildList {
            add(event.title)
            add(event.summary)
            add(event.message)
            add(event.status)
            add(event.severity?.wireValue)
            add(event.tags.joinToString(" "))
            add(event.eventId)
            add(event.state.token)
            add(event.thingId)
            add(event.channelId)
            event.timeline.forEach { row -> add(row.messageId) }
        },
    )
}

internal fun thingMatchesSearch(thing: ThingCardModel, rawQuery: String): Boolean {
    return matchesNormalizedSubstring(
        rawQuery = rawQuery,
        candidates = buildList {
            add(thing.title)
            add(thing.summary)
            add(thing.tags.joinToString(" "))
            add(thing.thingId)
            add(thing.state)
            add(thing.channelId)
            add(thing.locationType)
            add(thing.locationValue)
            thing.externalIds
                .toSortedMap()
                .forEach { (key, value) -> add("$key $value") }
            add(thing.attrsJson)
            add(thing.metadataJson)
            thing.relatedMessages.forEach { related ->
                add(related.message.title)
                add(related.message.body)
                add(related.message.bodyPreview)
                add(related.message.id)
                add(related.message.messageId)
            }
            thing.relatedEvents.forEach { event -> add(event.eventId) }
            thing.relatedUpdates.forEach { update -> add(update.updateId) }
        },
    )
}

internal fun shouldAutoloadEntitySearch(
    hasActiveSearch: Boolean,
    hasMore: Boolean,
    isLoading: Boolean,
    scannedPages: Int = 0,
    maxScanPages: Int = Int.MAX_VALUE,
): Boolean = hasActiveSearch &&
    hasMore &&
    !isLoading &&
    scannedPages < maxScanPages

internal fun shouldLoadMoreEntityPage(
    hasMore: Boolean,
    isLoading: Boolean,
    hasActiveSearch: Boolean,
    automaticSearchLoad: Boolean,
    scannedPages: Int,
    maxScanPages: Int,
): Boolean = hasMore &&
    !isLoading &&
    (!automaticSearchLoad || !hasActiveSearch || scannedPages < maxScanPages)

private fun matchesNormalizedSubstring(
    rawQuery: String,
    candidates: Iterable<String?>,
): Boolean {
    val query = SearchTextNormalizer.normalizedQuery(rawQuery)
    if (query.isEmpty()) return true
    val searchableText = candidates
        .mapNotNull { value -> value?.let(SearchTextNormalizer::normalize)?.takeIf(String::isNotEmpty) }
        .joinToString(" ")
    return searchableText.contains(query)
}
