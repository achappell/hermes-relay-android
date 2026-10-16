package com.achappell.hermesrelay

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.os.BundleCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HomeVoiceNotificationTest {
    @get:Rule val composeRule = createComposeRule()
    @get:Rule val notifications = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    private fun notification(): Notification? = manager.activeNotifications
        .singleOrNull { it.notification.channelId == "voice_session" }?.notification

    private fun awaitNotification(predicate: (Notification) -> Boolean): Notification {
        var observed: Notification? = null
        composeRule.waitUntil(timeoutMillis = 5_000) {
            observed = notification()
            observed?.let(predicate) == true
        }
        return checkNotNull(observed)
    }

    /** Native PlaybackState timestamps have millisecond resolution; cross that boundary explicitly. */
    private fun awaitNativeClockAfter(timestamp: Long) {
        val advanced = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val observe = object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() > timestamp) advanced.countDown()
                else handler.postDelayed(this, 1)
            }
        }
        handler.post(observe)
        assertTrue("native monotonic clock advanced", advanced.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun notification_text_stays_stable_while_media_state_and_controls_change_then_disappear() {
        composeRule.setContent { Text("Notification control verification") }
        assertNull("do not replace another active service", HomeVoiceService.instance)
        val original = HomeVoiceService.latest
        assertFalse("do not replace a retained user reply", original.active)
        val foreground = ServiceForegroundController(context, DiagnosticsJournal.None)
        val playing = VoiceServiceState(active = true, paused = false, appStopped = false)
        var sessionDestroyed = CountDownLatch(1)
        val sessionCallback = object : MediaController.Callback() {
            override fun onSessionDestroyed() {
                sessionDestroyed.countDown()
            }
        }
        var observedController: MediaController? = null
        val application = context.applicationContext as HermesRelayApplication
        val accounting = application.mediaSessionWrites
        val beforeWrites = accounting.snapshot()
        val beforeEnded = application.journal.snapshot().count { it.event.startsWith("voice response ended") }
        val sink = AudioTrackAudioSink(
            driverFactory = platformAudioTrackDriverFactory(AndroidPlatform.current(context)),
            journal = application.journal,
            mediaSessionWrites = accounting,
        )
        sink.prepareTurn()
        try {
            composeRule.runOnIdle { foreground.sync(playing) }
            val plain = awaitNotification { it.actions.isNullOrEmpty() }
            val descriptiveText = plain.extras.getCharSequence(Notification.EXTRA_TEXT)

            composeRule.runOnIdle {
                accounting.backgroundEntered()
                foreground.sync(playing.copy(appStopped = true))
            }
            val media = awaitNotification { it.actions?.size == 2 }
            val token = checkNotNull(
                BundleCompat.getParcelable(media.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java),
            )
            val controller = MediaController(context, token)
            observedController = controller
            controller.registerCallback(sessionCallback, Handler(Looper.getMainLooper()))
            assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState?.state)
            assertEquals(descriptiveText, media.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(beforeWrites.first + 1, accounting.snapshot().first)
            assertEquals(beforeWrites.second, accounting.snapshot().second)
            assertEquals(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP,
                controller.playbackState?.actions,
            )
            // Change notification metadata without changing native playback state.
            composeRule.runOnIdle {
                foreground.sync(playing.copy(appStopped = true, listening = true))
            }
            awaitNotification { it.extras.getCharSequence(Notification.EXTRA_TEXT) != descriptiveText }
            assertEquals(beforeWrites.first + 1, accounting.snapshot().first)
            assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState?.state)
            val pauseAction = media.actions[0].actionIntent
            val stopAction = media.actions[1].actionIntent

            composeRule.runOnIdle { foreground.sync(playing.copy(paused = true, appStopped = true)) }
            val paused = awaitNotification {
                controller.playbackState?.state == PlaybackState.STATE_PAUSED &&
                    it.actions?.size == 2 &&
                    it.actions[0].actionIntent != pauseAction &&
                    it.extras.getCharSequence(Notification.EXTRA_TEXT) == descriptiveText
            }
            assertEquals("phase changes must not invalidate queued notification speech", descriptiveText,
                paused.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(stopAction, paused.actions[1].actionIntent)
            assertEquals(beforeWrites.first + 2, accounting.snapshot().first)
            val pausedNativeUpdate = checkNotNull(controller.playbackState).lastPositionUpdateTime
            awaitNativeClockAfter(pausedNativeUpdate)
            composeRule.runOnIdle {
                foreground.sync(playing.copy(paused = true, appStopped = true, listening = true))
            }
            awaitNotification { it.extras.getCharSequence(Notification.EXTRA_TEXT) != descriptiveText }
            // notify follows the native write. PAUSED getters return its stored timestamp;
            // PLAYING getters synthesize an updated position/time and are not a write oracle.
            assertEquals(pausedNativeUpdate, checkNotNull(controller.playbackState).lastPositionUpdateTime)
            assertEquals(beforeWrites.first + 2, accounting.snapshot().first)

            composeRule.runOnIdle { foreground.sync(playing.copy(appStopped = true)) }
            val resumed = awaitNotification {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    it.actions?.firstOrNull()?.actionIntent == pauseAction &&
                    it.extras.getCharSequence(Notification.EXTRA_TEXT) == descriptiveText
            }
            assertEquals(descriptiveText, resumed.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(stopAction, resumed.actions[1].actionIntent)
            assertEquals(token,
                BundleCompat.getParcelable(resumed.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))
            assertEquals(beforeWrites.first + 3, accounting.snapshot().first)
            assertEquals(beforeWrites.second, accounting.snapshot().second)
            // Controlled service destruction, not a claim about natural OS death/runtime recovery.
            // Keep the same sink response and background baseline across actual native sessions.
            composeRule.runOnIdle { context.stopService(Intent(context, HomeVoiceService::class.java)) }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                HomeVoiceService.instance == null && notification() == null
            }
            assertTrue(sessionDestroyed.await(5, TimeUnit.SECONDS))
            controller.unregisterCallback(sessionCallback)
            sessionDestroyed = CountDownLatch(1)
            composeRule.runOnIdle { foreground.sync(playing.copy(appStopped = true)) }
            val recreated = awaitNotification { it.actions?.size == 2 }
            val recreatedToken = checkNotNull(
                BundleCompat.getParcelable(recreated.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java),
            )
            assertTrue("a new native session owns a fresh state cache", recreatedToken != token)
            val recreatedController = MediaController(context, recreatedToken)
            observedController = recreatedController
            recreatedController.registerCallback(sessionCallback, Handler(Looper.getMainLooper()))
            assertEquals(PlaybackState.STATE_PLAYING, recreatedController.playbackState?.state)
            assertEquals(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP,
                recreatedController.playbackState?.actions,
            )
            assertEquals(beforeWrites.first + 4, accounting.snapshot().first)
            assertEquals(beforeWrites.second, accounting.snapshot().second)
            assertTrue(sink.start(AndroidAudioFormat(16000, 1, 2, "pcm_s16le")))
            sink.write(ByteArray(320))
            val drained = CountDownLatch(1)
            var failure: String? = null
            sink.finish({ drained.countDown() }, { failure = it; drained.countDown() })
            assertTrue("native AudioTrack completion", drained.await(10, TimeUnit.SECONDS))
            assertNull(failure)
            composeRule.waitUntil(timeoutMillis = 5_000) {
                application.journal.snapshot().count { it.event.startsWith("voice response ended") } > beforeEnded
            }
            val ended = application.journal.snapshot().filter { it.event.startsWith("voice response ended") }
            assertEquals(beforeEnded + 1, ended.size)
            assertTrue(ended.last().event.matches(Regex("voice response ended underruns=\\d+ mediasession_writes=4")))

            composeRule.runOnIdle { foreground.sync(playing.copy(active = false)) }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                HomeVoiceService.instance == null && notification() == null
            }
            assertTrue("ending retention must release the media session", sessionDestroyed.await(5, TimeUnit.SECONDS))
        } finally {
            sink.close()
            accounting.foregroundEntered()
            observedController?.unregisterCallback(sessionCallback)
            HomeVoiceService.latest = original
            composeRule.runOnIdle { context.stopService(Intent(context, HomeVoiceService::class.java)) }
            composeRule.waitUntil(timeoutMillis = 5_000) { HomeVoiceService.instance == null }
        }
    }
}
