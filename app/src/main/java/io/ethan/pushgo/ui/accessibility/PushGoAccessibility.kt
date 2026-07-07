package io.ethan.pushgo.ui.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

@Suppress("DEPRECATION")
fun announceForAccessibility(context: Context, message: String) {
    val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (manager == null || !manager.isEnabled) return
    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    event.text.add(message)
    event.packageName = context.packageName
    event.className = context.javaClass.name
    manager.sendAccessibilityEvent(event)
}

fun joinAccessibilitySummary(vararg parts: String?): String {
    return parts
        .asSequence()
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(separator = ", ")
}

fun Modifier.pushGoDecorativeSemantics(): Modifier {
    return clearAndSetSemantics { }
}

fun Modifier.pushGoPaneSemantics(title: String?): Modifier {
    if (title.isNullOrBlank()) return this
    return semantics { paneTitle = title }
}

fun Modifier.pushGoLiveRegion(mode: LiveRegionMode = LiveRegionMode.Polite): Modifier {
    return semantics { liveRegion = mode }
}

fun Modifier.pushGoMergedActionSemantics(
    summary: String,
    onClickLabel: String,
    onClickAction: () -> Unit,
    modifierRole: Role = Role.Button,
    stateDescription: String? = null,
    selectedState: Boolean? = null,
    onLongClickLabel: String? = null,
    onLongClickAction: (() -> Unit)? = null,
    customActions: List<CustomAccessibilityAction> = emptyList(),
): Modifier {
    return semantics(mergeDescendants = true) {
        contentDescription = summary
        role = modifierRole
        stateDescription?.let { this.stateDescription = it }
        selectedState?.let { this.selected = it }
        onClick(label = onClickLabel) {
            onClickAction()
            true
        }
        if (onLongClickLabel != null && onLongClickAction != null) {
            onLongClick(label = onLongClickLabel) {
                onLongClickAction()
                true
            }
        }
        if (customActions.isNotEmpty()) {
            this.customActions = customActions
        }
    }
}
