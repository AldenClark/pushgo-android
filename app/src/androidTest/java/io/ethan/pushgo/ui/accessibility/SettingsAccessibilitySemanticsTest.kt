package io.ethan.pushgo.ui.accessibility

import androidx.activity.ComponentActivity
import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.data.model.ChannelSubscription
import io.ethan.pushgo.ui.screens.ChannelRow
import io.ethan.pushgo.ui.screens.SettingsToggleRow
import io.ethan.pushgo.ui.screens.DocumentationSettingsRow
import io.ethan.pushgo.ui.screens.PushGoDocumentationPage
import io.ethan.pushgo.ui.theme.PushGoTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

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

    @Test
    fun productionDocumentationRows_useLocalizedSemanticsAndRouteEveryPage() {
        val opened = mutableListOf<PushGoDocumentationPage>()
        composeRule.setContent {
            PushGoTheme {
                Column {
                    PushGoDocumentationPage.entries.forEach { page ->
                        DocumentationSettingsRow(page) { opened += it }
                    }
                }
            }
        }

        val rows = listOf(
            Triple(
                "row.settings.docs.getting_started",
                R.string.label_docs_getting_started,
                R.string.label_docs_getting_started_hint,
            ),
            Triple(
                "row.settings.docs.message_api",
                R.string.label_docs_message_api,
                R.string.label_docs_message_api_hint,
            ),
            Triple("row.settings.docs.e2ee", R.string.label_docs_e2ee, R.string.label_docs_e2ee_hint),
        )
        rows.forEach { (tag, titleRes, subtitleRes) ->
            val title = composeRule.activity.getString(titleRes)
            val subtitle = composeRule.activity.getString(subtitleRes)
            val description = composeRule.activity.getString(R.string.a11y_open_documentation, title, subtitle)
            assertTrue(title.isNotBlank() && subtitle.isNotBlank() && description.isNotBlank())
            composeRule.onNodeWithTag(tag).assertContentDescriptionEquals(description).performClick()
        }
        composeRule.runOnIdle {
            assertTrue(opened == PushGoDocumentationPage.entries)
        }
    }

    @Test
    fun documentationRowsHaveCompleteEnglishSimplifiedAndTraditionalChineseResources() {
        val resourcePairs = listOf(
            R.string.label_docs_getting_started to R.string.label_docs_getting_started_hint,
            R.string.label_docs_message_api to R.string.label_docs_message_api_hint,
            R.string.label_docs_e2ee to R.string.label_docs_e2ee_hint,
        )
        listOf("en-US", "zh-CN", "zh-TW").forEach { languageTag ->
            val configuration = Configuration(composeRule.activity.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(languageTag))
            }
            val localized = composeRule.activity.createConfigurationContext(configuration)
            resourcePairs.forEach { (titleRes, subtitleRes) ->
                val title = localized.getString(titleRes)
                val subtitle = localized.getString(subtitleRes)
                val semantics = localized.getString(R.string.a11y_open_documentation, title, subtitle)
                assertTrue("missing documentation resource for $languageTag", title.isNotBlank())
                assertTrue("missing documentation hint for $languageTag", subtitle.isNotBlank())
                assertTrue("missing documentation semantics for $languageTag", semantics.isNotBlank())
            }
        }
    }
}
