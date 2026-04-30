package com.example.cameraapp.webrtc

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages the WebRTC peer-connection lifecycle.
 *
 * The Android device acts as the **answerer** (surveillance camera side):
 *  - Sends video (from CameraX) and audio (microphone via WebRTC audio engine)
 *  - Receives audio for talkback
 *
 * Signaling messages (offer, answer, ICE candidates) are delivered by
 * [SignalingServer] via the callbacks set before calling [createPeerConnection].
 */
class WebRTCManager(private val context: Context) {

    companion object {
        private const val TAG = "WebRTCManager"
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
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private val videoCapturer = CameraXVideoCapturer()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /** Called when a local ICE candidate is ready to be sent to the remote peer. */
    var onIceCandidate: ((IceCandidate) -> Unit)? = null

    /** Called when the local SDP answer has been set – send it to the remote peer. */
    var onLocalDescription: ((SessionDescription) -> Unit)? = null

    /** Called when a remote audio track is received (talkback). */
    var onRemoteAudioTrack: ((AudioTrack) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Initialise the [PeerConnectionFactory] and media tracks.
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

            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglContext)

            // Video
            videoSource = peerConnectionFactory!!.createVideoSource(/* isScreencast= */ false)
            videoCapturer.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
            videoCapturer.startCapture(1280, 720, 30)

            localVideoTrack = peerConnectionFactory!!.createVideoTrack("v0", videoSource)
            localVideoTrack?.setEnabled(true)

            // Audio (WebRTC built-in engine handles mic capture)
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory!!.createAudioTrack("a0", audioSource)
            localAudioTrack?.setEnabled(true)

            Log.d(TAG, "WebRTCManager initialised")
        }
    }

    // -------------------------------------------------------------------------
    // Frame injection
    // -------------------------------------------------------------------------

    /** Forward a CameraX [ImageProxy] into the WebRTC video pipeline. */
    fun onCameraFrame(imageProxy: ImageProxy) {
        videoCapturer.onFrameAvailable(imageProxy)
    }

    // -------------------------------------------------------------------------
    // Peer connection
    // -------------------------------------------------------------------------

    /**
     * Create a new [PeerConnection] ready to handle an incoming offer.
     * Call this just before [handleOffer].
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
                buildPeerConnectionObserver()
            ) ?: run {
                Log.e(TAG, "createPeerConnection returned null")
                return@execute
            }

            // Add local tracks
            peerConnection!!.addTrack(localVideoTrack!!, listOf(STREAM_ID))
            peerConnection!!.addTrack(localAudioTrack!!, listOf(STREAM_ID))

            _connectionState.value = ConnectionState.Connecting
            Log.d(TAG, "PeerConnection created")
        }
    }

    /**
     * Apply the remote SDP offer received via signaling and create an answer.
     */
    fun handleOffer(sdpString: String) {
        executor.execute {
            val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            peerConnection?.setRemoteDescription(
                object : SimpleSdpObserver("SetRemoteSDP") {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Remote SDP set – creating answer")
                        createAnswer()
                    }
                },
                remoteDescription
            )
        }
    }

    private fun createAnswer() {
        // Called from executor thread
        val answerConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection?.createAnswer(
            object : SimpleSdpObserver("CreateAnswer") {
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
            answerConstraints
        )
    }

    /** Add a remote ICE candidate received via signaling. */
    fun addIceCandidate(candidate: IceCandidate) {
        executor.execute {
            peerConnection?.addIceCandidate(candidate)
        }
    }

    /** Close and destroy the current peer connection (peer disconnected). */
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
            videoCapturer.stopCapture()
            videoCapturer.dispose()
            localVideoTrack?.dispose()
            localAudioTrack?.dispose()
            videoSource?.dispose()
            audioSource?.dispose()
            peerConnection?.close()
            peerConnection = null
            surfaceTextureHelper?.dispose()
            peerConnectionFactory?.dispose()
            eglBase?.release()
            Log.d(TAG, "Released")
        }
        executor.shutdown()
    }

    // -------------------------------------------------------------------------
    // PeerConnection.Observer
    // -------------------------------------------------------------------------

    private fun buildPeerConnectionObserver() = object : PeerConnection.Observer {

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
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
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                Log.d(TAG, "New local ICE candidate: ${it.sdp}")
                onIceCandidate?.invoke(it)
            }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        @Deprecated("Deprecated in newer WebRTC versions; onAddTrack handles the same event")
        override fun onAddStream(stream: MediaStream?) {
            // Handled by onAddTrack – no action needed here
        }

        override fun onRemoveStream(stream: MediaStream?) {}

        override fun onDataChannel(channel: DataChannel?) {}

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return
            if (track is AudioTrack) {
                track.setEnabled(true)
                onRemoteAudioTrack?.invoke(track)
            }
        }
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
