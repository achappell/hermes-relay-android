package com.achappell.hermesrelay

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shows_honest_bootstrap_state() {
        composeRule.onNodeWithText("Android Client bootstrap").assertIsDisplayed()
        composeRule
            .onNodeWithText("The native Android surface is alive, but Hermes session transport is not connected yet.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Conversation, response audio, Profiles, Local History, and Device administration arrive as independently verified slices.")
            .assertIsDisplayed()
    }
}
