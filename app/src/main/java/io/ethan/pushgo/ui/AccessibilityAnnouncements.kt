package io.ethan.pushgo.ui

import android.content.Context
import io.ethan.pushgo.ui.accessibility.announceForAccessibility as sharedAnnounceForAccessibility

fun announceForAccessibility(context: Context, message: String) {
    sharedAnnounceForAccessibility(context, message)
}
