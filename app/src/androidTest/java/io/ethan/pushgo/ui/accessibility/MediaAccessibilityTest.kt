package io.ethan.pushgo.ui.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ethan.pushgo.R
import io.ethan.pushgo.ui.screens.PushGoPlayableImage
import io.ethan.pushgo.ui.screens.ZoomableImagePreviewDialog
import io.ethan.pushgo.ui.theme.PushGoTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaAccessibilityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun imagePreviewDialog_exposesPaneAndActionLabels() {
        composeRule.setContent {
            PushGoTheme {
                ZoomableImagePreviewDialog(
                    model = "https://example.com/image.png",
                    onDismiss = {},
                    onSaveImage = {},
                    onShareImage = {},
                )
            }
        }

        composeRule
            .onNode(hasPaneTitle(composeRule.activity.getString(R.string.a11y_pane_image_preview)))
            .assertExists()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.a11y_action_save_image)).assertExists()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.a11y_action_share_image)).assertExists()
        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.a11y_action_close_image_preview)).assertExists()
    }

    @Test
    fun playableImage_exposesExplicitPlayAction() {
        composeRule.setContent {
            PushGoTheme {
                PushGoPlayableImage(
                    model = "https://example.com/animated.gif",
                    contentDescription = "Preview image",
                    knownAnimated = true,
                    showPlayOverlayWhenIdle = true,
                    onPlayClick = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.a11y_action_play_image))
            .assertExists()
    }
}
