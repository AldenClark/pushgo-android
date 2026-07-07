package io.ethan.pushgo.ui.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.ui.screens.ChannelRow
import io.ethan.pushgo.ui.screens.SettingsToggleRow
import io.ethan.pushgo.ui.theme.PushGoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAccessibilitySemanticsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsToggleRow_exposesStateAndTogglesThroughMergedSemantics() {
        composeRule.setContent {
            PushGoTheme {
                var checked by remember { mutableStateOf(true) }
                SettingsToggleRow(
                    testTag = "toggle.settings.notifications",
                    icon = Icons.Default.Search,
                    title = "Notifications",
                    subtitle = "Receive urgent alerts",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        val onLabel = composeRule.activity.getString(R.string.a11y_state_on)
        val offLabel = composeRule.activity.getString(R.string.a11y_state_off)
        val node = composeRule.onNode(hasContentDescriptionContaining("Notifications"))
        node.assert(hasStateDescription(onLabel))
        node.performClick()
        node.assert(hasStateDescription(offLabel))
    }

    @Test
    fun channelRow_exposesCopyActionThroughRowSemantics() {
        var copied = false
        val subscription = ChannelSubscription(
            channelId = "ops-core",
            displayName = "Ops Core",
            updatedAt = 0L,
            lastSyncedAt = null,
        )

        composeRule.setContent {
            PushGoTheme {
                ChannelRow(
                    subscription = subscription,
                    onRename = {},
                    onDelete = {},
                    onCopy = { copied = true },
                )
            }
        }

        composeRule.onNode(hasContentDescriptionContaining("ops-core")).performClick()
        composeRule.runOnIdle {
            assertTrue(copied)
        }
    }
}
