package com.achappell.hermesrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidComposerStateTest {
    @Test
    fun a_disconnected_draft_stays_editable_but_send_is_blocked() {
        assertEquals(
            AndroidComposerBlock.Disconnected,
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = false,
                hasAcceptedTurn = false,
                prompt = "check the weather",
            ),
        )
    }

    @Test
    fun an_empty_prompt_explains_why_send_is_disabled_when_ready() {
        assertEquals(
            AndroidComposerBlock.EmptyPrompt,
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = true,
                hasAcceptedTurn = false,
                prompt = "   ",
            ),
        )
    }

    @Test
    fun an_active_turn_blocks_a_second_submission() {
        assertEquals(
            AndroidComposerBlock.ActiveTurn,
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = true,
                hasAcceptedTurn = true,
                prompt = "another prompt",
            ),
        )
    }

    @Test
    fun an_unconfirmed_turn_blocks_a_new_submission_until_the_user_decides() {
        assertEquals(
            AndroidComposerBlock.UnconfirmedTurn,
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = true,
                hasAcceptedTurn = false,
                prompt = "send again",
                hasUnconfirmedTurn = true,
            ),
        )
    }

    @Test
    fun a_missing_profile_is_the_first_composer_boundary() {
        assertEquals(
            AndroidComposerBlock.NoProfile,
            resolveAndroidComposerBlock(
                hasProfile = false,
                isAuthorized = false,
                isConnected = false,
                hasAcceptedTurn = false,
                prompt = "",
            ),
        )
    }

    @Test
    fun an_authorization_gap_is_distinct_from_a_missing_profile() {
        assertEquals(
            AndroidComposerBlock.Authorization,
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = false,
                isConnected = false,
                hasAcceptedTurn = false,
                prompt = "",
            ),
        )
    }

    @Test
    fun a_ready_composer_has_no_blocking_reason() {
        assertNull(
            resolveAndroidComposerBlock(
                hasProfile = true,
                isAuthorized = true,
                isConnected = true,
                hasAcceptedTurn = false,
                prompt = "check the weather",
            ),
        )
    }
}
