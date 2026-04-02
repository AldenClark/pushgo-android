package io.ethan.pushgo.notifications

object ForegroundNotificationPresentationState {
    private enum class ActiveList {
        MESSAGE,
        EVENT,
        THING,
    }

    private data class Snapshot(
        val activeList: ActiveList? = null,
        val suppressAtTop: Boolean = false,
    )

    @Volatile
    private var snapshot: Snapshot = Snapshot()

    fun reportMessage(isAtTop: Boolean, suppressionEligible: Boolean) {
        report(ActiveList.MESSAGE, isAtTop, suppressionEligible)
    }

    fun reportEvent(isAtTop: Boolean, suppressionEligible: Boolean) {
        report(ActiveList.EVENT, isAtTop, suppressionEligible)
    }

    fun reportThing(isAtTop: Boolean, suppressionEligible: Boolean) {
        report(ActiveList.THING, isAtTop, suppressionEligible)
    }

    fun clearMessage() {
        clear(ActiveList.MESSAGE)
    }

    fun clearEvent() {
        clear(ActiveList.EVENT)
    }

    fun clearThing() {
        clear(ActiveList.THING)
    }

    fun shouldSuppress(entityType: String): Boolean {
        val target = when (entityType.trim().lowercase()) {
            "message" -> ActiveList.MESSAGE
            "event" -> ActiveList.EVENT
            "thing" -> ActiveList.THING
            else -> return false
        }
        val current = snapshot
        return current.activeList == target && current.suppressAtTop
    }

    private fun report(
        activeList: ActiveList,
        isAtTop: Boolean,
        suppressionEligible: Boolean,
    ) {
        snapshot = Snapshot(
            activeList = activeList,
            suppressAtTop = suppressionEligible && isAtTop,
        )
    }

    private fun clear(activeList: ActiveList) {
        val current = snapshot
        if (current.activeList == activeList) {
            snapshot = Snapshot()
        }
    }
}
