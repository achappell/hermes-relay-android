package com.achappell.hermesrelay

import android.speech.SpeechRecognizer
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRecognitionPolicyTest {
    private fun platform(api: Int) = AndroidPlatform(
        apiLevel = api,
        notificationsPermitted = true,
        foregroundServiceSupported = true,
    )

    @Test
    fun every_recognizer_error_has_an_explicit_mapping() {
        val expected = mapOf(
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to AndroidSpeechFailure.NetworkUnavailable,
            SpeechRecognizer.ERROR_NETWORK to AndroidSpeechFailure.NetworkUnavailable,
            SpeechRecognizer.ERROR_AUDIO to AndroidSpeechFailure.AudioUnavailable,
            SpeechRecognizer.ERROR_SERVER to AndroidSpeechFailure.RecognizerUnavailable,
            SpeechRecognizer.ERROR_CLIENT to AndroidSpeechFailure.RecognizerUnavailable,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to AndroidSpeechFailure.NoSpeechHeard,
            SpeechRecognizer.ERROR_NO_MATCH to AndroidSpeechFailure.NoSpeechHeard,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to AndroidSpeechFailure.RecognizerBusy,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to AndroidSpeechFailure.PermissionRequired,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to AndroidSpeechFailure.RecognizerBusy,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED to AndroidSpeechFailure.RecognizerUnavailable,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to AndroidSpeechFailure.LanguageUnavailable,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to AndroidSpeechFailure.LanguageUnavailable,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT to AndroidSpeechFailure.RecognizerUnavailable,
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS to
                AndroidSpeechFailure.RecognizerUnavailable,
        )
        expected.forEach { (code, failure) ->
            assertEquals("error $code", failure, speechFailureFor(code))
        }
    }

    @Test
    fun the_table_covers_every_error_constant_the_compile_sdk_defines() {
        // A future SDK that adds an ERROR_* constant must fail here until the
        // new code is mapped, rather than quietly reading as "Unknown".
        val defined = SpeechRecognizer::class.java.fields
            .filter { it.name.startsWith("ERROR_") && Modifier.isStatic(it.modifiers) }
            .map { it.getInt(null) }
        assertTrue("no ERROR_ constants found by reflection", defined.size >= 15)
        defined.forEach { code ->
            assertNotEquals("error $code reads as Unknown", AndroidSpeechFailure.Unknown, speechFailureFor(code))
        }
    }

    @Test
    fun only_a_code_android_has_not_defined_is_unknown() {
        assertEquals(AndroidSpeechFailure.Unknown, speechFailureFor(0))
        assertEquals(AndroidSpeechFailure.Unknown, speechFailureFor(999))
    }

    @Test
    fun the_recognizer_is_on_device_by_construction_when_it_can_be() {
        assertEquals(
            RecognizerKind.OnDevice,
            chooseRecognizer(platform(31), onDeviceRecognitionAvailable = true, networkAllowed = false),
        )
        assertEquals(
            RecognizerKind.OnDevice,
            chooseRecognizer(platform(37), onDeviceRecognitionAvailable = true, networkAllowed = false),
        )
    }

    @Test
    fun the_system_recognizer_is_used_below_api_31_or_without_an_on_device_recognizer() {
        assertEquals(
            RecognizerKind.SystemDefault,
            chooseRecognizer(platform(30), onDeviceRecognitionAvailable = true, networkAllowed = false),
        )
        assertEquals(
            RecognizerKind.SystemDefault,
            chooseRecognizer(platform(37), onDeviceRecognitionAvailable = false, networkAllowed = false),
        )
    }

    @Test
    fun allowing_the_network_uses_the_system_recognizer_even_when_an_on_device_one_exists() {
        assertEquals(
            RecognizerKind.SystemDefault,
            chooseRecognizer(platform(37), onDeviceRecognitionAvailable = true, networkAllowed = true),
        )
    }

    private fun support(installed: Set<String> = emptySet(), online: Set<String> = emptySet()) =
        RecognitionSupportSnapshot(installedOnDevice = installed, onlineSupported = online)

    @Test
    fun an_installed_pack_listens_on_device() {
        assertEquals(
            RecognitionPlan.Listen(preferOffline = true),
            planRecognition(RecognitionRequest("en-US", false, support(installed = setOf("en-US")))),
        )
        // Even with the network allowed, an installed pack keeps audio on the device.
        assertEquals(
            RecognitionPlan.Listen(preferOffline = true),
            planRecognition(RecognitionRequest("en-US", true, support(installed = setOf("en-US")))),
        )
    }

    @Test
    fun a_missing_pack_without_consent_never_falls_back_to_the_network() {
        assertEquals(
            RecognitionPlan.LanguagePackMissing,
            planRecognition(
                RecognitionRequest("en-US", false, support(installed = setOf("fr-FR"), online = setOf("en-US"))),
            ),
        )
        assertEquals(
            RecognitionPlan.LanguagePackMissing,
            planRecognition(RecognitionRequest("en-US", false, support())),
        )
    }

    @Test
    fun a_missing_pack_with_consent_listens_online() {
        assertEquals(
            RecognitionPlan.Listen(preferOffline = false),
            planRecognition(RecognitionRequest("en-US", true, support(online = setOf("en-US")))),
        )
    }

    @Test
    fun without_a_pre_flight_the_network_decision_follows_consent_alone() {
        assertEquals(
            RecognitionPlan.Listen(preferOffline = true),
            planRecognition(RecognitionRequest("en-US", false, support = null)),
        )
        assertEquals(
            RecognitionPlan.Listen(preferOffline = false),
            planRecognition(RecognitionRequest("en-US", true, support = null)),
        )
    }

    @Test
    fun language_matching_respects_region_only_when_both_sides_name_one() {
        assertTrue(sameLanguage("en-US", "en-US"))
        assertTrue(sameLanguage("en_US", "en-us"))
        assertTrue(sameLanguage("en", "en-GB"))
        assertTrue(sameLanguage("en-GB", "en"))
        assertEquals(false, sameLanguage("en-US", "en-GB"))
        assertEquals(false, sameLanguage("en-US", "fr-US"))
    }
}
