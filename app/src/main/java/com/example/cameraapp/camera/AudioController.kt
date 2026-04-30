package com.example.cameraapp.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Manages audio routing and focus for the surveillance / talkback session.
 *
 * WebRTC's internal audio engine handles actual microphone capture and
 * remote-audio playback; this class only configures Android's AudioManager
 * so that the right physical paths are active.
 *
 * [AudioFocusRequest] is used (API 26+, matching the app's minSdk) instead
 * of the deprecated [AudioManager.requestAudioFocus] overload.
 */
class AudioController(private val context: Context) {

    companion object {
        private const val TAG = "AudioController"
    }

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioFocusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN
    ).apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        setAcceptsDelayedFocusGain(false)
        setOnAudioFocusChangeListener { focusChange ->
            Log.d(TAG, "Audio focus change: $focusChange")
        }
    }.build()

    /** Switch between speakerphone and earpiece. */
    fun setSpeakerOn(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
        Log.d(TAG, "Speakerphone: $enabled")
    }

    /** Mute or un-mute the device microphone. */
    fun setMicrophoneMute(muted: Boolean) {
        audioManager.isMicrophoneMute = muted
        Log.d(TAG, "Microphone muted: $muted")
    }

    /**
     * Set the audio mode to [AudioManager.MODE_IN_COMMUNICATION] which is
     * optimal for VoIP / WebRTC.
     */
    fun setAudioMode(mode: Int = AudioManager.MODE_IN_COMMUNICATION) {
        audioManager.mode = mode
        Log.d(TAG, "Audio mode set to $mode")
    }

    /** Restore normal audio mode and release speakerphone. */
    fun restoreAudioMode() {
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.isSpeakerphoneOn = false
        Log.d(TAG, "Audio mode restored")
    }

    /**
     * Request audio focus for the voice-call stream so other media apps
     * duck / pause during streaming.
     */
    fun requestAudioFocus() {
        val result = audioManager.requestAudioFocus(audioFocusRequest)
        Log.d(TAG, "Audio focus request result: $result")
    }

    fun abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
        Log.d(TAG, "Audio focus abandoned")
    }
}
