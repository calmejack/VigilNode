package com.example.cameraapp.webrtc

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * A [VideoCapturer] implementation that receives YUV_420_888 frames produced by
 * CameraX's [ImageAnalysis] use-case and forwards them to the WebRTC pipeline
 * via the [CapturerObserver].
 *
 * Usage:
 * 1. Pass an instance to [WebRTCManager] which calls [initialize].
 * 2. Feed frames by calling [onFrameAvailable] from your ImageAnalysis analyzer.
 */
class CameraXVideoCapturer : VideoCapturer {

    companion object {
        private const val TAG = "CameraXVideoCapturer"
    }

    @Volatile
    private var capturerObserver: CapturerObserver? = null

    @Volatile
    private var isCapturing = false

    // -------------------------------------------------------------------------
    // VideoCapturer interface
    // -------------------------------------------------------------------------

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context?,
        capturerObserver: CapturerObserver?
    ) {
        this.capturerObserver = capturerObserver
        Log.d(TAG, "Initialized")
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        isCapturing = true
        capturerObserver?.onCapturerStarted(true)
        Log.d(TAG, "startCapture ${width}x${height} @ ${framerate}fps")
    }

    override fun stopCapture() {
        isCapturing = false
        capturerObserver?.onCapturerStopped()
        Log.d(TAG, "stopCapture")
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        Log.d(TAG, "changeCaptureFormat ${width}x${height} @ ${framerate}fps")
    }

    override fun dispose() {
        capturerObserver = null
        Log.d(TAG, "Disposed")
    }

    override fun isScreencast(): Boolean = false

    // -------------------------------------------------------------------------
    // Frame injection from CameraX
    // -------------------------------------------------------------------------

    /**
     * Called from the [ImageAnalysis] executor with each new camera frame.
     * Converts the YUV_420_888 [ImageProxy] to a [VideoFrame] (I420 buffer)
     * and dispatches it to the WebRTC engine.
     *
     * **Always closes [imageProxy] before returning.**
     */
    fun onFrameAvailable(imageProxy: ImageProxy) {
        if (!isCapturing || capturerObserver == null) {
            imageProxy.close()
            return
        }

        try {
            val timestampNs = imageProxy.imageInfo.timestamp
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val i420Buffer = imageProxy.toI420Buffer()
            if (i420Buffer != null) {
                val videoFrame = VideoFrame(i420Buffer, rotationDegrees, timestampNs)
                capturerObserver?.onFrameCaptured(videoFrame)
                videoFrame.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame", e)
        } finally {
            imageProxy.close()
        }
    }

    // -------------------------------------------------------------------------
    // YUV_420_888 → I420 conversion
    // -------------------------------------------------------------------------

    private fun ImageProxy.toI420Buffer(): JavaI420Buffer? {
        return try {
            val yPlane = planes[0]
            val uPlane = planes[1]
            val vPlane = planes[2]

            val imgWidth = width
            val imgHeight = height
            val chromaWidth = (imgWidth + 1) / 2
            val chromaHeight = (imgHeight + 1) / 2

            val i420Buffer = JavaI420Buffer.allocate(imgWidth, imgHeight)

            copyPlane(
                yPlane.buffer, yPlane.rowStride, yPlane.pixelStride,
                i420Buffer.dataY, imgWidth, imgHeight
            )
            copyPlane(
                uPlane.buffer, uPlane.rowStride, uPlane.pixelStride,
                i420Buffer.dataU, chromaWidth, chromaHeight
            )
            copyPlane(
                vPlane.buffer, vPlane.rowStride, vPlane.pixelStride,
                i420Buffer.dataV, chromaWidth, chromaHeight
            )

            i420Buffer
        } catch (e: Exception) {
            Log.e(TAG, "I420 conversion failed", e)
            null
        }
    }

    /**
     * Copies a single plane from [src] (with arbitrary row/pixel strides) into
     * [dst] (tightly packed, 1 byte per pixel).
     */
    private fun copyPlane(
        src: ByteBuffer,
        srcRowStride: Int,
        srcPixelStride: Int,
        dst: ByteBuffer,
        planeWidth: Int,
        planeHeight: Int
    ) {
        src.rewind()
        dst.clear()

        if (srcPixelStride == 1 && srcRowStride == planeWidth
            && src.remaining() >= planeWidth * planeHeight) {
            // Fast path: src is already tightly packed and has enough data
            val limit = min(planeWidth * planeHeight, src.remaining())
            val slice = src.duplicate()
            slice.limit(slice.position() + limit)
            dst.put(slice)
        } else {
            val rowBuf = ByteArray(srcRowStride)
            for (row in 0 until planeHeight) {
                val srcPos = row * srcRowStride
                if (srcPos + srcRowStride > src.limit()) break
                src.position(srcPos)
                src.get(rowBuf, 0, min(srcRowStride, src.remaining()))
                for (col in 0 until planeWidth) {
                    dst.put(rowBuf[col * srcPixelStride])
                }
            }
        }

        dst.rewind()
    }
}
