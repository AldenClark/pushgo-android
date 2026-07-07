package io.ethan.pushgo.ui.accessibility

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher

internal fun hasContentDescriptionContaining(text: String): SemanticsMatcher {
    return SemanticsMatcher("contentDescription contains $text") { node ->
        val descriptions = runCatching { node.config[SemanticsProperties.ContentDescription] }.getOrNull()
        descriptions?.any { it.contains(text) } == true
    }
}

internal fun hasPaneTitle(title: String): SemanticsMatcher {
    return SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title)
}

internal fun hasStateDescription(description: String): SemanticsMatcher {
    return SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)
}

internal fun hasLiveRegion(mode: LiveRegionMode): SemanticsMatcher {
    return SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, mode)
}
