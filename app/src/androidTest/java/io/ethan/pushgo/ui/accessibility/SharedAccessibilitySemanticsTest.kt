package io.ethan.pushgo.ui.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.PendingLocalDeletionBar
import io.ethan.pushgo.ui.PendingLocalDeletionCoordinator
import io.ethan.pushgo.ui.screens.PushGoCircularActionIconButton
import io.ethan.pushgo.ui.screens.PushGoModalBottomSheet
import io.ethan.pushgo.ui.screens.PushGoSearchBar
import io.ethan.pushgo.ui.theme.PushGoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedAccessibilitySemanticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun searchBar_exposesLabelAndEmptyState() {
        val searchLabel = composeRule.activity.getString(R.string.label_search)
        val emptyLabel = composeRule.activity.getString(R.string.a11y_value_empty)

        composeRule.setContent {
            PushGoTheme {
                PushGoSearchBar(
                    value = "",
                    onValueChange = {},
                    placeholderText = searchLabel,
                )
            }
        }

        composeRule
            .onNode(hasContentDescriptionContaining(searchLabel))
            .assert(hasStateDescription(emptyLabel))
    }

    @Test
    fun circularActionButton_exposesAccessibleLabel() {
        val actionLabel = composeRule.activity.getString(R.string.a11y_action_delete_message)

        composeRule.setContent {
            PushGoTheme {
                PushGoCircularActionIconButton(
                    imageVector = Icons.Default.Search,
                    accessibilityLabel = actionLabel,
                    onClick = {},
                    containerColor = androidx.compose.ui.graphics.Color.Red,
                    contentColor = androidx.compose.ui.graphics.Color.White,
                )
            }
        }

        composeRule.onNodeWithContentDescription(actionLabel).assertExists()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun modalBottomSheet_exposesPaneTitle() {
        val paneTitle = composeRule.activity.getString(R.string.a11y_pane_gateway_settings)

        composeRule.setContent {
            PushGoTheme {
                PushGoModalBottomSheet(
                    onDismissRequest = {},
                    paneTitle = paneTitle,
                ) {
                    Box {
                        androidx.compose.material3.Text(text = paneTitle)
                    }
                }
            }
        }

        composeRule.onNode(hasPaneTitle(paneTitle)).assertExists()
    }

    @Test
    fun pendingDeletionBar_announcesUndoWindowAsLiveRegion() {
        val pendingDeletion = PendingLocalDeletionCoordinator.PendingDeletion(
            id = 1L,
            summary = "Delete message",
            scope = PendingLocalDeletionCoordinator.Scope(messageIds = setOf("message-1")),
            deadlineElapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime() + 3_000L,
        )

        composeRule.setContent {
            PushGoTheme {
                PendingLocalDeletionBar(
                    pendingDeletion = pendingDeletion,
                    onUndo = {},
                )
            }
        }

        composeRule
            .onNode(hasContentDescriptionContaining("Undo available"))
            .assert(hasLiveRegion(LiveRegionMode.Polite))
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.label_undo)).assertExists()
    }
}
