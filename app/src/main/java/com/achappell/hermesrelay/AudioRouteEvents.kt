package com.achappell.hermesrelay

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper

internal enum class AudioRouteEvent { BecomingNoisy, DeviceRemoved, SelectionChanged }
internal data class OutputRoute(val id: Int, val personal: Boolean)

/** Captured before dispatch: a later reconnection must not erase removal evidence. */
internal data class AudioRouteChange(
    val event: AudioRouteEvent,
    val selected: List<OutputRoute>,
    val removedIds: Set<Int> = emptySet(),
)

internal interface AudioRouteEvents {
    fun observe(listener: (AudioRouteChange) -> Unit)
    fun stop()
    fun selectedRoutes(): List<OutputRoute>

    object None : AudioRouteEvents {
        override fun observe(listener: (AudioRouteChange) -> Unit) = Unit
        override fun stop() = Unit
        override fun selectedRoutes(): List<OutputRoute> = emptyList()
    }
}

/** Public Android query boundary; inventories of connected devices are never selection. */
internal interface AudioRouteQueries {
    fun trackRoutes(): List<OutputRoute>
    fun mediaRoutes(): List<OutputRoute>
}

/** Device-free production evidence logic, including registration/track ownership. */
internal class AudioRouteEvidence(private val queries: AudioRouteQueries) : AudioRouteEvents {
    private val removed = mutableSetOf<Int>()
    private var generation = 0L
    private var listener: ((AudioRouteChange) -> Unit)? = null
    @Volatile private var track: Any? = null

    override fun selectedRoutes(): List<OutputRoute> {
        val actual = queries.trackRoutes()
        val selected = if (actual.isNotEmpty()) actual else queries.mediaRoutes()
        return if (removed.isEmpty()) selected.toList() else selected.filterNot { it.id in removed }
    }

    override fun observe(listener: (AudioRouteChange) -> Unit) {
        generation++
        this.listener = listener
    }

    override fun stop() { generation++; listener = null }

    fun registration(): Registration = Registration(generation)
    fun attachTrack(track: Any) { this.track = track }
    fun detachTrack(track: Any) { if (this.track === track) this.track = null }
    fun routingChanged(track: Any) {
        if (this.track === track) registration().changed()
    }

    inner class Registration internal constructor(private val expected: Long) {
        private fun current() = expected == generation && listener != null
        fun added(ids: Set<Int>) {
            if (!current()) return
            removed.removeAll(ids)
            changed()
        }
        fun removed(ids: Set<Int>) {
            if (!current()) return
            removed.addAll(ids)
            emit(AudioRouteEvent.DeviceRemoved, ids.toSet())
        }
        fun noisy() { if (current()) emit(AudioRouteEvent.BecomingNoisy) }
        fun changed() { if (current()) emit(AudioRouteEvent.SelectionChanged) }
        private fun emit(event: AudioRouteEvent, removedIds: Set<Int> = emptySet()) {
            listener?.invoke(AudioRouteChange(event, selectedRoutes(), removedIds))
        }
    }
}

/** Application-owned Android registrations; a track listener lives only with its track. */
internal class PlatformAudioRouteEvents(context: Context) : AudioRouteEvents {
    private val context = context.applicationContext
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    @Volatile private var track: AudioTrack? = null
    private val evidence = AudioRouteEvidence(object : AudioRouteQueries {
        override fun trackRoutes(): List<OutputRoute> = runCatching {
            val current = track ?: return@runCatching emptyList()
            if (Build.VERSION.SDK_INT >= 36) current.routedDevices.map { it.outputRoute() }
            else current.routedDevice?.let { listOf(it.outputRoute()) }.orEmpty()
        }.getOrDefault(emptyList())
        override fun mediaRoutes(): List<OutputRoute> = runCatching {
            // API 26–32 has no public inactive-media selection query. Unknown stays held.
            if (Build.VERSION.SDK_INT >= 33) {
                manager?.getAudioDevicesForAttributes(mediaAttributes)?.map { it.outputRoute() }.orEmpty()
            } else emptyList()
        }.getOrDefault(emptyList())
    })
    private var devices: AudioDeviceCallback? = null
    private var receiver: BroadcastReceiver? = null
    private val routing = AudioRouting.OnRoutingChangedListener { evidence.routingChanged(it) }

    fun attach(track: AudioTrack) {
        this.track = track
        evidence.attachTrack(track)
        track.addOnRoutingChangedListener(routing, main)
    }

    fun detach(track: AudioTrack) {
        track.removeOnRoutingChangedListener(routing)
        evidence.detachTrack(track)
        if (this.track === track) this.track = null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun observe(listener: (AudioRouteChange) -> Unit) {
        stop()
        evidence.observe(listener)
        val registration = evidence.registration()
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                registration.added(addedDevices.filter { it.isSink }.mapTo(mutableSetOf()) { it.id })
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                val ids = removedDevices.filter { it.isSink }.mapTo(mutableSetOf()) { it.id }
                if (ids.isNotEmpty()) registration.removed(ids)
            }
        }
        devices = callback
        manager?.registerAudioDeviceCallback(callback, main)
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) registration.noisy()
            }
        }
        receiver = noisy
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(noisy, filter)
    }

    override fun stop() {
        evidence.stop()
        devices?.let { manager?.unregisterAudioDeviceCallback(it) }
        devices = null
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    override fun selectedRoutes(): List<OutputRoute> = evidence.selectedRoutes()

    private fun AudioDeviceInfo.outputRoute() = OutputRoute(
        id,
        type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET,
    )
}
