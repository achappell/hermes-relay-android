package com.achappell.hermesrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a Home reply alive after the app stops (`ANDROID-HOME-08`).
 *
 * A thin shell: it owns no runtime state. [HomeRuntime] decides when a reply is
 * retained and tells it through [ServiceForegroundController]; the shell shows a
 * content-free notification, publishes a media card with Pause/Play and Stop
 * once the app has been stopped for a moment, and holds a bounded wake lock.
 * Controls go back to the runtime on the main thread.
 *
 * The service is started while the app is visible and never from the stop
 * callback. Every start call re-asserts the foreground state at once, because
 * the platform requires `startForeground` soon after `startForegroundService`.
 */
class HomeVoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    @Volatile
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shown: VoiceServiceState? = null

    @Volatile
    private var cardPublished = false

    @Volatile
    private var ended = false

    /** True once this instance is shutting down; the controller then starts a new one. */
    internal val isEnding: Boolean
        get() = ended

    private val journal: DiagnosticsJournal
        get() = (application as? HermesRelayApplication)?.journal ?: DiagnosticsJournal.None

    private val publishCard = Runnable {
        if (!ended) shown?.takeIf { it.active && it.appStopped }?.let { publishCard(it) }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("hermes-voice-card").also { it.start() }
        worker = Handler(thread.looper)
        createChannel()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> main.post { runtime()?.pauseFromControl() }
            ACTION_PLAY -> main.post { runtime()?.playFromControl() }
            ACTION_STOP -> main.post { runtime()?.stopFromControl() }
        }
        val state = latest
        if (state.active && ended) {
            // A new reply arrived while the last one was shutting down: stay up.
            ended = false
            shown = null
        }
        val card = session
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                // A control tap must not replace the card with a bare notification.
                if (cardPublished && card != null) mediaNotification(state, card) else plainNotification(state),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
        }
        if (started.isFailure) {
            journal.record("fgs start failed class=${started.exceptionOrNull()?.javaClass?.simpleName}")
            end("startFailed")
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            journal.record("fgs started type=mediaPlayback")
            acquireWakeLock()
        }
        worker.post { applyOnThread(state) }
        return START_NOT_STICKY
    }

    /** Swiping the app away ends retained work once; nothing is replayed. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        main.post { runtime()?.taskRemoved() }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ended = true
        instance = null
        worker.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        releaseWakeLock()
        thread.quitSafely()
        journal.record("fgs stopped")
        super.onDestroy()
    }

    /** Called by the controller when the runtime's state changed; written only on change. */
    internal fun apply(state: VoiceServiceState) {
        worker.post { applyOnThread(state) }
    }

    private fun applyOnThread(state: VoiceServiceState) {
        if (ended || state == shown) return
        shown = state
        if (!state.active) {
            end("retentionEnded")
            return
        }
        worker.removeCallbacks(publishCard)
        when {
            cardPublished -> publishCard(state)
            // A quick peek at the lock screen never registers a card.
            state.appStopped -> worker.postDelayed(publishCard, CARD_DEFER_MILLIS)
            else -> notify(plainNotification(state))
        }
    }

    private fun end(reason: String) {
        if (ended) return
        ended = true
        journal.record("fgs end reason=$reason")
        main.post {
            // A new reply may have restarted this instance since.
            if (!ended) return@post
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun publishCard(state: VoiceServiceState) {
        val startedAt = SystemClock.elapsedRealtime()
        val created = session == null
        val current = session ?: MediaSession(this, SESSION_TAG).also { fresh ->
            fresh.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        main.post { runtime()?.playFromControl() }
                    }

                    override fun onPause() {
                        main.post { runtime()?.pauseFromControl() }
                    }

                    override fun onStop() {
                        main.post { runtime()?.stopFromControl() }
                    }
                },
                worker,
            )
            fresh.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.android_voice_card_title))
                    .build(),
            )
            fresh.isActive = true
            session = fresh
        }
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                .setState(
                    if (state.paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (state.paused) 0f else 1f,
                )
                .build(),
        )
        notify(mediaNotification(state, current))
        cardPublished = true
        if (created) {
            journal.record(
                "mediasession registered took_ms=${SystemClock.elapsedRealtime() - startedAt} thread=background",
            )
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.android_voice_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun baseNotification(state: VoiceServiceState): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hermes)
            // Content-free: never the prompt, the reply or a conversation title.
            .setContentTitle(getString(R.string.android_voice_card_title))
            .setContentText(
                getString(if (state.paused) R.string.android_voice_paused else R.string.android_voice_replying),
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .also {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    it.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }

    private fun plainNotification(state: VoiceServiceState): Notification =
        baseNotification(state).build()

    private fun mediaNotification(state: VoiceServiceState, session: MediaSession): Notification {
        val toggle = if (state.paused) {
            action(ACTION_PLAY, android.R.drawable.ic_media_play, R.string.android_voice_action_play)
        } else {
            action(ACTION_PAUSE, android.R.drawable.ic_media_pause, R.string.android_voice_action_pause)
        }
        return baseNotification(state)
            .addAction(toggle)
            .addAction(
                action(ACTION_STOP, android.R.drawable.ic_menu_close_clear_cancel, R.string.android_voice_action_stop),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun action(name: String, icon: Int, label: Int): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(this, icon),
            getString(label),
            PendingIntent.getService(
                this,
                name.hashCode(),
                Intent(this, HomeVoiceService::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    private fun runtime(): HomeRuntime? =
        (application as? HermesRelayApplication)?.homeRuntimeBox?.current()

    private fun acquireWakeLock() {
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MILLIS)
        }
        journal.record("wakelock acquired")
    }

    private fun releaseWakeLock() {
        val held = wakeLock ?: return
        wakeLock = null
        if (held.isHeld) held.release()
        journal.record("wakelock released")
    }

    companion object {
        internal const val ACTION_PAUSE = "com.achappell.hermesrelay.action.VOICE_PAUSE"
        internal const val ACTION_PLAY = "com.achappell.hermesrelay.action.VOICE_PLAY"
        internal const val ACTION_STOP = "com.achappell.hermesrelay.action.VOICE_STOP"
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 7401
        private const val SESSION_TAG = "HermesReply"
        private const val WAKE_LOCK_TAG = "hermes:reply"

        /** The full card waits this long after a stop, so a quick look away registers nothing. */
        private const val CARD_DEFER_MILLIS = 750L

        /** A backstop: the runtime releases it as soon as the reply ends. */
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 10 * 60 * 1000L

        /** The newest state the runtime asked for; read by a service that is still starting. */
        @Volatile
        internal var latest = VoiceServiceState(active = false, paused = false, appStopped = false)

        @Volatile
        internal var instance: HomeVoiceService? = null
    }
}

/** The platform [ForegroundServiceController]: starts [HomeVoiceService] and feeds it state. */
internal class ServiceForegroundController(
    private val context: Context,
    private val journal: DiagnosticsJournal,
) : ForegroundServiceController {
    override fun sync(state: VoiceServiceState) {
        HomeVoiceService.latest = state
        HomeVoiceService.instance?.takeUnless { it.isEnding }?.let { running ->
            running.apply(state)
            return
        }
        if (!state.active) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, HomeVoiceService::class.java))
        }.onFailure { error ->
            journal.record("fgs start failed class=${error.javaClass.simpleName}")
        }
    }
}
