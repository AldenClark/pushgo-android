package io.ethan.pushgo.ui.accessibility

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.ethan.pushgo.R

@Composable
internal fun selectionStateDescription(selected: Boolean): String {
    return stringResource(if (selected) R.string.a11y_state_selected else R.string.a11y_state_not_selected)
}

@Composable
internal fun toggleStateDescription(enabled: Boolean): String {
    return stringResource(if (enabled) R.string.a11y_state_on else R.string.a11y_state_off)
}

@Composable
internal fun messageReadStateDescription(isRead: Boolean): String {
    return stringResource(if (isRead) R.string.a11y_state_read else R.string.a11y_state_unread)
}

@Composable
internal fun eventLifecycleStateDescription(isClosed: Boolean): String {
    return stringResource(if (isClosed) R.string.a11y_state_closed else R.string.a11y_state_open)
}

@Composable
internal fun selectionModeAnnouncement(selectedCount: Int): String {
    return if (selectedCount > 0) {
        stringResource(R.string.a11y_selection_mode_selection_count, selectedCount)
    } else {
        stringResource(R.string.a11y_selection_mode_entered)
    }
}
