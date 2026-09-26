package dev.deeplinks.native.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.deeplinks.core.DshTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun filterChip_exposesSelectionAndMinimumTouchHeight() {
        composeRule.setContent {
            DshTheme {
                DshFilterChip(
                    label = "Running",
                    selected = true,
                    onClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Running")
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun banner_exposesPoliteLiveRegionAndAccessibleActionHeight() {
        composeRule.setContent {
            DshTheme {
                DshBanner(
                    text = "Connection restored",
                    actionLabel = "Retry",
                    onAction = {},
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            )
        ).assertExists()
        composeRule.onNodeWithContentDescription("Retry")
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun singleLineTextField_hasBoundedHeightAndAccessibleName() {
        composeRule.setContent {
            DshTheme {
                DshTextField(
                    value = "Device name",
                    onValueChange = {},
                    contentDescription = "Rename device",
                )
            }
        }

        composeRule.onNodeWithContentDescription("Rename device")
            .assertHeightIsEqualTo(48.dp)
    }
}
