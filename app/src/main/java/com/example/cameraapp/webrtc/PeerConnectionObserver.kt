package com.example.cameraapp.webrtc

import android.util.Log
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver

/**
 * Reusable [PeerConnection.Observer] implementation.
 *
 * Provides callbacks for key WebRTC events:
 * - ICE candidate generation
 * - ICE connection state changes
 * - Remote track addition
 * - Connection state changes
 *
 * Optional callbacks can be set via constructor parameters.
 * Unhandled events are logged but otherwise ignored.
 */
class PeerConnectionObserver(
    private val onIceCandidate: ((IceCandidate) -> Unit)? = null,
    private val onIceConnectionChange: ((PeerConnection.IceConnectionState?) -> Unit)? = null,
    private val onConnectionChange: ((PeerConnection.PeerConnectionState?) -> Unit)? = null,
    private val onAddTrack: ((MediaStreamTrack) -> Unit)? = null,
    private val onRemoveTrack: ((MediaStreamTrack) -> Unit)? = null,
    private val onSignalingChange: ((PeerConnection.SignalingState?) -> Unit)? = null,
    private val onIceGatheringChange: ((PeerConnection.IceGatheringState?) -> Unit)? = null,
    private val onRenegotiationNeeded: (() -> Unit)? = null,
    private val onDataChannel: ((DataChannel) -> Unit)? = null
) : PeerConnection.Observer {

    companion object {
        private const val TAG = "PeerConnectionObserver"
    }

    // -------------------------------------------------------------------------
    // Connection state callbacks
    // -------------------------------------------------------------------------

    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
        Log.d(TAG, "Signaling state: $state")
        onSignalingChange?.invoke(state)
    }

    override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
        Log.d(TAG, "Connection state: $state")
        onConnectionChange?.invoke(state)
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        Log.d(TAG, "ICE connection state: $state")
        onIceConnectionChange?.invoke(state)
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Log.d(TAG, "ICE connection receiving: $receiving")
    }

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
        Log.d(TAG, "ICE gathering state: $state")
        onIceGatheringChange?.invoke(state)
    }

    // -------------------------------------------------------------------------
    // ICE candidate callbacks
    // -------------------------------------------------------------------------

    override fun onIceCandidate(candidate: IceCandidate?) {
        candidate?.let {
            Log.d(TAG, "New ICE candidate: ${it.sdpMid}:${it.sdpMLineIndex}")
            onIceCandidate?.invoke(it)
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        Log.d(TAG, "ICE candidates removed: ${candidates?.size ?: 0}")
    }

    // -------------------------------------------------------------------------
    // Track callbacks
    // -------------------------------------------------------------------------

    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
        val track = receiver?.track() ?: return
        Log.d(TAG, "Remote track added: ${track.kind()} (id=${track.id()})")
        onAddTrack?.invoke(track)
    }

    override fun onRemoveTrack(receiver: RtpReceiver?) {
        val track = receiver?.track() ?: return
        Log.d(TAG, "Remote track removed: ${track.kind()} (id=${track.id()})")
        onRemoveTrack?.invoke(track)
    }

    @Deprecated("Deprecated in newer WebRTC versions; onAddTrack handles the same event")
    override fun onAddStream(stream: MediaStream?) {
        // Handled by onAddTrack – no action needed here
        Log.d(TAG, "onAddStream (deprecated): ${stream?.id}")
    }

    override fun onRemoveStream(stream: MediaStream?) {
        Log.d(TAG, "onRemoveStream (deprecated): ${stream?.id}")
    }

    // -------------------------------------------------------------------------
    // Other callbacks
    // -------------------------------------------------------------------------

    override fun onDataChannel(channel: DataChannel?) {
        channel?.let {
            Log.d(TAG, "Data channel: ${it.label()}")
            onDataChannel?.invoke(it)
        }
    }

    override fun onRenegotiationNeeded() {
        Log.d(TAG, "Renegotiation needed")
        onRenegotiationNeeded?.invoke()
    }
}
