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

    @Test
    fun notification_text_stays_stable_while_media_state_and_controls_change_then_disappear() {
        composeRule.setContent { Text("Notification control verification") }
        assertNull("do not replace another active service", HomeVoiceService.instance)
        val original = HomeVoiceService.latest
        assertFalse("do not replace a retained user reply", original.active)
        val foreground = ServiceForegroundController(context, DiagnosticsJournal.None)
        val playing = VoiceServiceState(active = true, paused = false, appStopped = false)
        val sessionDestroyed = CountDownLatch(1)
        val sessionCallback = object : MediaController.Callback() {
            override fun onSessionDestroyed() {
                sessionDestroyed.countDown()
            }
        }
        var observedController: MediaController? = null
        try {
            composeRule.runOnIdle { foreground.sync(playing) }
            val plain = awaitNotification { it.actions.isNullOrEmpty() }
            val descriptiveText = plain.extras.getCharSequence(Notification.EXTRA_TEXT)

            composeRule.runOnIdle { foreground.sync(playing.copy(appStopped = true)) }
            val media = awaitNotification { it.actions?.size == 2 }
            val token = checkNotNull(
                BundleCompat.getParcelable(media.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java),
            )
            val controller = MediaController(context, token)
            observedController = controller
            controller.registerCallback(sessionCallback, Handler(Looper.getMainLooper()))
            assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState?.state)
            assertEquals(descriptiveText, media.extras.getCharSequence(Notification.EXTRA_TEXT))
            val pauseAction = media.actions[0].actionIntent
            val stopAction = media.actions[1].actionIntent

            composeRule.runOnIdle { foreground.sync(playing.copy(paused = true, appStopped = true)) }
            val paused = awaitNotification {
                controller.playbackState?.state == PlaybackState.STATE_PAUSED &&
                    it.actions?.firstOrNull()?.actionIntent != pauseAction
            }
            assertEquals("phase changes must not invalidate queued notification speech", descriptiveText,
                paused.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(stopAction, paused.actions[1].actionIntent)

            composeRule.runOnIdle { foreground.sync(playing.copy(appStopped = true)) }
            val resumed = awaitNotification {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    it.actions?.firstOrNull()?.actionIntent == pauseAction
            }
            assertEquals(descriptiveText, resumed.extras.getCharSequence(Notification.EXTRA_TEXT))
            assertEquals(stopAction, resumed.actions[1].actionIntent)
            assertEquals(token,
                BundleCompat.getParcelable(resumed.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))

            composeRule.runOnIdle { foreground.sync(playing.copy(active = false)) }
            composeRule.waitUntil(timeoutMillis = 5_000) {
                HomeVoiceService.instance == null && notification() == null
            }
            assertTrue("ending retention must release the media session", sessionDestroyed.await(5, TimeUnit.SECONDS))
        } finally {
            observedController?.unregisterCallback(sessionCallback)
            HomeVoiceService.latest = original
            composeRule.runOnIdle { context.stopService(Intent(context, HomeVoiceService::class.java)) }
            composeRule.waitUntil(timeoutMillis = 5_000) { HomeVoiceService.instance == null }
        }
    }
}
