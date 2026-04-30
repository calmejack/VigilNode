package com.example.cameraapp.recording

import android.content.Context
import android.util.Log
import com.example.cameraapp.camera.CameraController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages circular video recording using CameraX's [VideoCapture][Recorder].
 *
 * Recordings are split into [SEGMENT_DURATION_MS]-long segments and stored
 * under the app's internal files directory.  When the total size of all
 * segments exceeds [MAX_STORAGE_MB], the oldest files are deleted
 * automatically before starting a new segment.
 */
class RecordingManager(
    private val context: Context,
    private val cameraController: CameraController
) {

    companion object {
        private const val TAG = "RecordingManager"
        private const val VIDEO_DIR = "recordings"
        private const val VIDEO_EXT = ".mp4"
        private const val SEGMENT_DURATION_MS = 5L * 60L * 1000L   // 5 minutes
        private const val MAX_STORAGE_MB = 500L                     // 500 MB limit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rotationJob: Job? = null

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val recordingDir: File by lazy {
        File(context.filesDir, VIDEO_DIR).also { it.mkdirs() }
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Begin circular recording. Safe to call multiple times – no-op if already recording. */
    fun startRecording() {
        if (_recordingState.value is RecordingState.Recording) {
            Log.w(TAG, "Already recording – ignoring startRecording()")
            return
        }
        Log.d(TAG, "Starting recording")
        startSegment()
        scheduleRotation()
    }

    /** Stop recording and cancel segment rotation. */
    fun stopRecording() {
        rotationJob?.cancel()
        rotationJob = null
        cameraController.stopRecording()
        _recordingState.value = RecordingState.Idle
        Log.d(TAG, "Recording stopped")
    }

    /** Returns all recorded files, newest first. */
    fun getRecordingFiles(): List<File> =
        recordingDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Returns total storage used by recordings in megabytes. */
    fun getStorageUsageMB(): Long {
        val bytes = recordingDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sumOf { it.length() } ?: 0L
        return bytes / (1024L * 1024L)
    }

    fun release() {
        stopRecording()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun startSegment() {
        enforceStorageLimit()
        val file = createNewFile()
        _recordingState.value = RecordingState.Recording(file.name)

        cameraController.startRecording(
            outputFile = file,
            onComplete = { saved ->
                Log.d(TAG, "Segment finalised: ${saved.name} (${saved.length() / 1024} KB)")
            },
            onError = { msg ->
                Log.e(TAG, "Segment error: $msg")
                _recordingState.value = RecordingState.Error(msg)
            }
        )
    }

    private fun scheduleRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            while (isActive) {
                delay(SEGMENT_DURATION_MS)
                if (!isActive) break
                Log.d(TAG, "Rotating recording segment")
                // Stop the current segment (triggers Finalize event) then start a new one.
                // The 500 ms pause gives the MediaMuxer time to flush and close the file
                // before we call startRecording() on the same CameraController instance.
                cameraController.stopRecording()
                delay(500)
                startSegment()
            }
        }
    }

    private fun createNewFile(): File {
        val timestamp = dateFormat.format(Date())
        return File(recordingDir, "REC_${timestamp}$VIDEO_EXT")
    }

    /**
     * Delete oldest files until total size is below [MAX_STORAGE_MB].
     * Called before every new segment so we never exceed the limit.
     */
    private fun enforceStorageLimit() {
        val files = recordingDir.listFiles()
            ?.filter { it.extension == "mp4" }
            ?.sortedBy { it.lastModified() }
            ?.toMutableList() ?: return

        var totalBytes = files.sumOf { it.length() }
        val maxBytes = MAX_STORAGE_MB * 1024L * 1024L

        while (totalBytes > maxBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            totalBytes -= oldest.length()
            if (oldest.delete()) {
                Log.d(TAG, "Deleted old segment: ${oldest.name}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(val fileName: String) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }
}
