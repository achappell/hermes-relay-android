package com.achappell.hermesrelay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * Audio focus for one reply (`ANDROID-HOME-08`). Requested once, at the first
 * audio of the reply, with a gain type chosen then and never changed:
 * `MAY_DUCK` in the foreground, `TRANSIENT` when the reply starts while the app
 * is stopped. Ducking, delayed gain and route changes are `ANDROID-HOME-09`.
 */
internal class PlatformAudioFocusController(context: Context) : AudioFocusController {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null

    override fun request(allowDuck: Boolean, onChange: (FocusChange) -> Unit): Boolean {
        val manager = audioManager ?: return false
        abandon()
        val gain = if (allowDuck) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
        val built = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onChange(FocusChange.LossPermanent)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onChange(FocusChange.LossTransient)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onChange(FocusChange.Duck)
                    AudioManager.AUDIOFOCUS_GAIN -> onChange(FocusChange.Gain)
                }
            }, main)
            .build()
        request = built
        return manager.requestAudioFocus(built) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        val current = request ?: return
        request = null
        audioManager?.abandonAudioFocusRequest(current)
    }
}
