package io.ethan.pushgo.ui

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager

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
