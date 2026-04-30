package com.example.cameraapp.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Manages all CameraX use-cases:
 *  - Preview  (optional – attach/detach from an Activity)
 *  - ImageAnalysis  (supplies frames to WebRTCManager)
 *  - VideoCapture<Recorder>  (local circular recording)
 */
class CameraController(private val context: Context) {

    companion object {
        private const val TAG = "CameraController"
        private val TARGET_RESOLUTION = Size(1280, 720)
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Idle)
    val cameraState: StateFlow<CameraState> = _cameraState

    /** Called with each YUV_420_888 frame from ImageAnalysis. Caller is responsible for closing ImageProxy. */
    var frameCallback: ((ImageProxy) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Binds all use-cases to [lifecycleOwner].
     * [onCameraReady] is invoked on the main thread once the camera is bound.
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        onCameraReady: () -> Unit = {}
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases(lifecycleOwner)
                _cameraState.value = CameraState.Running
                onCameraReady()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                _cameraState.value = CameraState.Error(e.message ?: "Unknown error")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return

        preview = Preview.Builder()
            .setTargetResolution(TARGET_RESOLUTION)
            .build()

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(TARGET_RESOLUTION)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    frameCallback?.invoke(imageProxy) ?: imageProxy.close()
                }
            }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        provider.unbindAll()
        provider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview!!,
            imageAnalysis!!,
            videoCapture!!
        )
    }

    // -------------------------------------------------------------------------
    // Preview surface management (called from Activity)
    // -------------------------------------------------------------------------

    /** Attach a PreviewView surface so the camera feed is rendered on-screen. */
    fun attachPreview(previewView: PreviewView) {
        preview?.setSurfaceProvider(previewView.surfaceProvider)
    }

    /** Detach the surface – preview stops rendering but camera keeps running. */
    fun detachPreview() {
        preview?.setSurfaceProvider(null)
    }

    // -------------------------------------------------------------------------
    // Recording
    // -------------------------------------------------------------------------

    /**
     * Start a new recording segment to [outputFile].
     * [onComplete] is called on the main thread when the file is finalised.
     * [onError] is called on the main thread on failure.
     */
    fun startRecording(
        outputFile: File,
        onComplete: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = videoCapture ?: run {
            Log.e(TAG, "VideoCapture not initialized – has startCamera() been called?")
            onError("VideoCapture not initialized")
            return
        }

        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> Log.d(TAG, "Segment started: ${outputFile.name}")
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e(TAG, "Recording error (code=${event.error}): ${event.cause?.message}")
                            onError(event.cause?.message ?: "Recording error code ${event.error}")
                        } else {
                            Log.d(TAG, "Segment saved: ${outputFile.name} (${outputFile.length() / 1024} KB)")
                            onComplete(outputFile)
                        }
                        activeRecording = null
                    }
                    else -> Unit
                }
            }
    }

    /** Stop the current recording segment (triggers Finalize event). */
    fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    fun stopCamera() {
        stopRecording()
        cameraProvider?.unbindAll()
        _cameraState.value = CameraState.Idle
    }

    fun release() {
        stopCamera()
        analysisExecutor.shutdown()
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    sealed class CameraState {
        object Idle : CameraState()
        object Running : CameraState()
        data class Error(val message: String) : CameraState()
    }
}
