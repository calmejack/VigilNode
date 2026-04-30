package com.example.cameraapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.cameraapp.databinding.ActivityMainBinding
import com.example.cameraapp.recording.RecordingManager
import com.example.cameraapp.service.CameraService
import com.example.cameraapp.signaling.SignalingServer
import com.example.cameraapp.webrtc.WebRTCManager
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityMainBinding

    private var cameraService: CameraService? = null
    private var serviceBound = false

    // -------------------------------------------------------------------------
    // Service connection
    // -------------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cameraService = (binder as CameraService.LocalBinder).getService()
            serviceBound = true
            onServiceReady()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            serviceBound = false
        }
    }

    // -------------------------------------------------------------------------
    // Permission launcher
    // -------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.all { it.value }) {
            startAndBindService()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required.", Toast.LENGTH_LONG).show()
            binding.tvStatus.text = getString(R.string.status_permissions_denied)
        }
    }

    // -------------------------------------------------------------------------
    // Activity lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        displayNetworkInfo()

        if (hasAllPermissions()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-attach preview whenever activity comes to foreground
        cameraService?.cameraController?.attachPreview(binding.previewView)
    }

    override fun onStop() {
        super.onStop()
        // Detach preview when going to background (camera keeps running in service)
        cameraService?.cameraController?.detachPreview()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupUI() {
        binding.btnStop.setOnClickListener { stopService() }
        binding.btnStart.setOnClickListener {
            if (hasAllPermissions()) startAndBindService()
            else permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun onServiceReady() {
        val service = cameraService ?: return

        // Attach preview surface
        service.cameraController.attachPreview(binding.previewView)

        // Observe WebRTC state
        service.webRTCManager.connectionState
            .onEach { state -> updateConnectionUI(state) }
            .launchIn(lifecycleScope)

        // Observe recording state
        service.recordingManager.recordingState
            .onEach { state -> updateRecordingUI(state) }
            .launchIn(lifecycleScope)

        binding.btnStart.visibility = View.GONE
        binding.btnStop.visibility  = View.VISIBLE
        Log.d(TAG, "Service ready – UI wired")
    }

    // -------------------------------------------------------------------------
    // Service management
    // -------------------------------------------------------------------------

    private fun startAndBindService() {
        val intent = Intent(this, CameraService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            cameraService = null
        }
        startService(Intent(this, CameraService::class.java).apply {
            action = CameraService.ACTION_STOP
        })
        binding.tvStatus.text = getString(R.string.status_stopped)
        binding.btnStart.visibility = View.VISIBLE
        binding.btnStop.visibility  = View.GONE
    }

    // -------------------------------------------------------------------------
    // UI updates
    // -------------------------------------------------------------------------

    private fun updateConnectionUI(state: WebRTCManager.ConnectionState) {
        binding.tvStatus.text = when (state) {
            is WebRTCManager.ConnectionState.Connected    -> getString(R.string.status_connected)
            is WebRTCManager.ConnectionState.Connecting  -> getString(R.string.status_connecting)
            is WebRTCManager.ConnectionState.Disconnected -> getString(R.string.status_waiting)
        }
    }

    private fun updateRecordingUI(state: RecordingManager.RecordingState) {
        binding.tvRecordingStatus.text = when (state) {
            is RecordingManager.RecordingState.Recording -> "⏺ ${state.fileName}"
            is RecordingManager.RecordingState.Idle      -> getString(R.string.recording_idle)
            is RecordingManager.RecordingState.Error     -> "⚠ ${state.message}"
        }
    }

    private fun displayNetworkInfo() {
        val ip = getLocalIpAddress() ?: "detecting…"
        binding.tvNetworkInfo.text =
            "IP: $ip  |  ws://$ip:${SignalingServer.DEFAULT_PORT}"
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun hasAllPermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) {
            Log.e(TAG, "Error reading network interfaces", e)
            null
        }
    }
}
