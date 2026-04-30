package com.example.cameraapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.cameraapp.MainActivity
import com.example.cameraapp.R
import com.example.cameraapp.camera.AudioController
import com.example.cameraapp.camera.CameraController
import com.example.cameraapp.recording.RecordingManager
import com.example.cameraapp.signaling.SignalingServer
import com.example.cameraapp.webrtc.WebRTCManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.webrtc.IceCandidate

/**
 * Long-running foreground service that owns the camera, WebRTC, signaling,
 * and recording sub-systems.
 *
 * Clients (e.g. [MainActivity]) bind to the service via [LocalBinder] to
 * access component references and attach the camera preview.
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_CHANNEL_ID = "camera_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.cameraapp.action.START"
        const val ACTION_STOP  = "com.example.cameraapp.action.STOP"
    }

    // -------------------------------------------------------------------------
    // Components (lazy so they are constructed only when first accessed)
    // -------------------------------------------------------------------------

    val cameraController: CameraController by lazy { CameraController(this) }
    val audioController: AudioController    by lazy { AudioController(this) }
    val webRTCManager: WebRTCManager        by lazy { WebRTCManager(this) }
    val recordingManager: RecordingManager  by lazy { RecordingManager(this, cameraController) }

    private var signalingServer: SignalingServer? = null

    // -------------------------------------------------------------------------
    // Binder
    // -------------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initialising…"))
        initComponents()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Stop action received")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Tear down in reverse order
        recordingManager.release()
        signalingServer?.stopServer()
        signalingServer = null
        webRTCManager.release()
        cameraController.release()
        audioController.abandonAudioFocus()
        audioController.restoreAudioMode()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    private fun initComponents() {
        // Configure audio for VoIP / WebRTC
        audioController.setAudioMode()
        audioController.requestAudioFocus()
        audioController.setSpeakerOn(true)

        // Initialise WebRTC factory + media tracks
        webRTCManager.initialize()

        // Wire WebRTC → SignalingServer callbacks
        webRTCManager.onIceCandidate = { candidate: IceCandidate ->
            signalingServer?.sendIceCandidate(
                candidate.sdpMid ?: "",
                candidate.sdpMLineIndex,
                candidate.sdp
            )
        }
        webRTCManager.onLocalDescription = { sessionDescription ->
            signalingServer?.sendAnswer(sessionDescription.description)
        }

        // Pipe CameraX frames into WebRTC
        cameraController.frameCallback = { imageProxy ->
            webRTCManager.onCameraFrame(imageProxy)
        }

        // Start the embedded signaling server
        startSignalingServer()

        // Start camera (bound to this service's lifecycle) – starts recording
        // when the camera is ready
        cameraController.startCamera(lifecycleOwner = this) {
            recordingManager.startRecording()
            Log.d(TAG, "Camera ready – recording started")
        }

        // Reflect WebRTC state in the persistent notification
        webRTCManager.connectionState
            .onEach { state -> updateNotification(state) }
            .launchIn(lifecycleScope)
    }

    private fun startSignalingServer() {
        signalingServer = SignalingServer(
            onOffer = { sdpString ->
                webRTCManager.createPeerConnection()
                webRTCManager.handleOffer(sdpString)
            },
            onIceCandidate = { sdpMid, sdpMLineIndex, sdp ->
                webRTCManager.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
            }
        )
        try {
            signalingServer!!.start()
            Log.i(TAG, "Signaling server started on port ${SignalingServer.DEFAULT_PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start signaling server", e)
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Camera Surveillance",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the P2P Camera service running in the background"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(subtitle: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CameraService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("P2P Camera")
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_camera_notification)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: WebRTCManager.ConnectionState) {
        val subtitle = when (state) {
            is WebRTCManager.ConnectionState.Connected    -> "Streaming – viewer connected"
            is WebRTCManager.ConnectionState.Connecting  -> "Connecting…"
            is WebRTCManager.ConnectionState.Disconnected -> "Waiting for viewer"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(subtitle))
    }
}
