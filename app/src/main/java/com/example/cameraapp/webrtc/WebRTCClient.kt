package com.example.cameraapp.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WebRTC client for the viewer side of the P2P surveillance camera.
 *
 * The viewer acts as the **offerer** (initiates the connection):
 *  - Receives video and audio from the remote camera
 *  - Sends audio for talkback (microphone via WebRTC audio engine)
 *
 * Signaling messages (offer, answer, ICE candidates) must be exchanged
 * via WebSocket or another signaling mechanism.
 */
class WebRTCClient(private val context: Context) {

    companion object {
        private const val TAG = "WebRTCClient"
        private const val STREAM_ID = "local_stream"
        private val ICE_SERVERS = listOf(
            // Use only local/Tailscale STUN – no external relay needed
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer()
        )
    }

    // Dedicated single-thread executor so all WebRTC calls are serialised
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** Called when a local ICE candidate is ready to be sent to the remote peer. */
    var onIceCandidate: ((IceCandidate) -> Unit)? = null

    /** Called when the local SDP offer has been set – send it to the remote peer. */
    var onLocalDescription: ((SessionDescription) -> Unit)? = null

    /** Called when a remote video track is received. */
    var onRemoteVideoTrack: ((VideoTrack) -> Unit)? = null

    /** Called when a remote audio track is received. */
    var onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Initialise the [PeerConnectionFactory] and local audio track.
     * Must be called once before [createPeerConnection].
     */
    fun initialize() {
        executor.execute {
            eglBase = EglBase.create()

            val initOptions = PeerConnectionFactory.InitializationOptions
                .builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)

            val eglContext = eglBase!!.eglBaseContext
            val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options())
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            // Audio (WebRTC built-in engine handles mic capture for talkback)
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory!!.createAudioTrack("a0", audioSource)
            localAudioTrack?.setEnabled(true)

            Log.d(TAG, "WebRTCClient initialised")
        }
    }

    // -------------------------------------------------------------------------
    // Peer connection
    // -------------------------------------------------------------------------

    /**
     * Create a new [PeerConnection] and generate an SDP offer.
     * The offer will be delivered via [onLocalDescription] callback.
     */
    fun createPeerConnection() {
        executor.execute {
            val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = peerConnectionFactory!!.createPeerConnection(
                rtcConfig,
                PeerConnectionObserver(
                    onIceCandidate = { candidate ->
                        Log.d(TAG, "New local ICE candidate: ${candidate.sdp}")
                        onIceCandidate?.invoke(candidate)
                    },
                    onIceConnectionChange = { state ->
                        Log.d(TAG, "ICE connection state: $state")
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED ->
                                _connectionState.value = ConnectionState.Connected

                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.FAILED,
                            PeerConnection.IceConnectionState.CLOSED ->
                                _connectionState.value = ConnectionState.Disconnected

                            else -> Unit
                        }
                    },
                    onAddTrack = { track ->
                        when (track) {
                            is VideoTrack -> {
                                track.setEnabled(true)
                                onRemoteVideoTrack?.invoke(track)
                            }
                            is AudioTrack -> {
                                track.setEnabled(true)
                                onRemoteAudioTrack?.invoke(track)
                            }
                        }
                    }
                )
            ) ?: run {
                Log.e(TAG, "createPeerConnection returned null")
                return@execute
            }

            // Add local audio track (for talkback)
            peerConnection!!.addTrack(localAudioTrack!!, listOf(STREAM_ID))

            _connectionState.value = ConnectionState.Connecting
            Log.d(TAG, "PeerConnection created")

            // Create the SDP offer
            createOffer()
        }
    }

    /**
     * Create an SDP offer to initiate the connection.
     */
    private fun createOffer() {
        // Called from executor thread
        val offerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createOffer(
            object : SimpleSdpObserver("CreateOffer") {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    peerConnection?.setLocalDescription(
                        object : SimpleSdpObserver("SetLocalSDP") {
                            override fun onSetSuccess() {
                                Log.d(TAG, "Local SDP set")
                                onLocalDescription?.invoke(sessionDescription)
                            }
                        },
                        sessionDescription
                    )
                }
            },
            offerConstraints
        )
    }

    /**
     * Apply the remote SDP answer received via signaling.
     */
    fun handleAnswer(sdpString: String) {
        executor.execute {
            val remoteDescription = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
            peerConnection?.setRemoteDescription(
                object : SimpleSdpObserver("SetRemoteSDP") {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote SDP answer set")
                    }
                },
                remoteDescription
            )
        }
    }

    /**
     * Add a remote ICE candidate received via signaling.
     */
    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
            Log.d(TAG, "Added remote ICE candidate")
        }
    }

    /**
     * Close and destroy the current peer connection.
     */
    fun closePeerConnection() {
        executor.execute {
            peerConnection?.close()
            peerConnection = null
            _connectionState.value = ConnectionState.Disconnected
            Log.d(TAG, "PeerConnection closed")
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    fun release() {
        executor.execute {
            localAudioTrack?.dispose()
            audioSource?.dispose()
            peerConnection?.close()
            peerConnection = null
            peerConnectionFactory?.dispose()
            eglBase?.release()
            Log.d(TAG, "Released")
        }
        executor.shutdown()
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
    }
}
