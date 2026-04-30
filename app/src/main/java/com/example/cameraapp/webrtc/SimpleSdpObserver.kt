package com.example.cameraapp.webrtc

import android.util.Log
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Convenience base class for [SdpObserver].
 *
 * Provides no-op default implementations so subclasses only override
 * the callbacks they care about.
 */
open class SimpleSdpObserver(private val tag: String = "SdpObserver") : SdpObserver {

    override fun onCreateSuccess(sessionDescription: SessionDescription) {
        Log.d(tag, "SDP create success: ${sessionDescription.type}")
    }

    override fun onSetSuccess() {
        Log.d(tag, "SDP set success")
    }

    override fun onCreateFailure(error: String) {
        Log.e(tag, "SDP create failure: $error")
    }

    override fun onSetFailure(error: String) {
        Log.e(tag, "SDP set failure: $error")
    }
}
