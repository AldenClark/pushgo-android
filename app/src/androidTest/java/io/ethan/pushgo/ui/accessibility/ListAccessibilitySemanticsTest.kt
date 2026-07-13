package io.ethan.pushgo.ui.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.data.model.DecryptionState
import io.ethan.pushgo.data.model.MessageListItem
import io.ethan.pushgo.data.model.MessageStatus
import io.ethan.pushgo.ui.screens.EventCardModel
import io.ethan.pushgo.ui.screens.EventLifecycleState
import io.ethan.pushgo.ui.screens.EventListRowItem
import io.ethan.pushgo.ui.screens.EventSeverity
import io.ethan.pushgo.ui.screens.MessageRow
import io.ethan.pushgo.ui.screens.ThingCardModel
import io.ethan.pushgo.ui.screens.ThingRow
import io.ethan.pushgo.ui.theme.PushGoTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListAccessibilitySemanticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun messageRow_exposesSummaryStateAndPrimaryActions() {
        val message = MessageListItem(
            id = "message-1",
            messageId = "message-1",
            title = "Server alert",
            channel = "ops",
            url = null,
            isRead = false,
            receivedAt = Instant.parse("2026-07-07T00:00:00Z"),
            listPayloadJson = """{"severity":"high","tags":"[\"ops\"]"}""",
            status = MessageStatus.NORMAL,
            decryptionState = DecryptionState.DECRYPT_OK,
            notificationId = null,
            serverId = null,
            bodyPreview = "CPU usage reached 95%",
        )

        composeRule.setContent {
            PushGoTheme {
                MessageRow(
                    message = message,
                    imageModels = listOf("https://example.com/image.png"),
                    channelDisplayName = "Ops",
                    onClick = {},
                    onMarkRead = {},
                    onDelete = {},
                    selectionMode = false,
                    selected = false,
                    onToggleSelection = {},
                )
            }
        }

        val unreadLabel = composeRule.activity.getString(R.string.a11y_state_unread)
        composeRule
            .onNode(hasContentDescriptionContaining("Server alert"))
            .assert(hasStateDescription(unreadLabel))
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.a11y_action_mark_message_read)).assertExists()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.a11y_action_delete_message)).assertExists()
    }

    @Test
    fun eventRow_exposesSummaryAndSelectionState() {
        val event = EventCardModel(
            eventId = "event-1",
            title = "Cooling failure",
            summary = "Rack temperature rising",
            status = "Investigating",
            message = "On-site technician dispatched",
            imageUrl = null,
            severity = EventSeverity.High,
            tags = listOf("ops", "cooling"),
            state = EventLifecycleState.Ongoing,
            thingId = "rack-42",
            channelId = "ops",
            decryptionState = null,
            attachmentUrls = emptyList(),
            attrsJson = null,
            updatedAt = Instant.parse("2026-07-07T00:00:00Z"),
            timeline = emptyList(),
        )

        composeRule.setContent {
            PushGoTheme {
                EventListRowItem(
                    event = event,
                    channelDisplayName = "Ops",
                    onClick = {},
                    selectionMode = true,
                    selected = true,
                    onToggleSelection = {},
                )
            }
        }

        val selectedLabel = composeRule.activity.getString(R.string.a11y_state_selected)
        composeRule
            .onNode(hasContentDescriptionContaining("Cooling failure"))
            .assert(hasStateDescription("Open, $selectedLabel"))
    }

    @Test
    fun thingRow_exposesSummaryAndOpenAction() {
        val thing = ThingCardModel(
            thingId = "thing-1",
            title = "Core router",
            summary = "Primary edge router",
            state = "active",
            channelId = "netops",
            decryptionState = null,
            imageUrl = null,
            tags = listOf("network"),
            createdAt = Instant.parse("2026-07-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-07-07T00:00:00Z"),
            imageUrls = emptyList(),
            attrsJson = null,
            metadataJson = null,
            relatedEvents = emptyList(),
            relatedMessages = emptyList(),
            relatedUpdates = emptyList(),
        )

        composeRule.setContent {
            PushGoTheme {
                ThingRow(
                    thing = thing,
                    channelDisplayName = "NetOps",
                    onClick = {},
                    selectionMode = false,
                    selected = false,
                    onToggleSelection = {},
                )
            }
        }

        composeRule.onNode(hasContentDescriptionContaining("Core router")).assertExists()
    }
}
