package com.example.cameraapp.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

/**
 * Embedded WebSocket signaling server running on the Android device.
 *
 * Protocol (JSON messages):
 *
 *   Client → Server:
 *     { "type": "offer",     "sdp": "<SDP string>" }
 *     { "type": "candidate", "candidate": { "sdpMid": "…", "sdpMLineIndex": 0, "candidate": "…" } }
 *     { "type": "ping" }
 *
 *   Server → Client:
 *     { "type": "answer",    "sdp": "<SDP string>" }
 *     { "type": "candidate", "candidate": { … } }
 *     { "type": "pong" }
 *
 * Only **one** client is served at a time; a second connection attempt is
 * rejected with close code 1008.
 */
class SignalingServer(
    port: Int = DEFAULT_PORT,
    private val onOffer: (sdp: String) -> Unit,
    private val onIceCandidate: (sdpMid: String, sdpMLineIndex: Int, sdp: String) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "SignalingServer"
        // Pre-serialised responses that never change
        private const val MSG_PONG = """{"type":"pong"}"""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentClient: WebSocket? = null

    // -------------------------------------------------------------------------
    // WebSocketServer callbacks
    // -------------------------------------------------------------------------

    override fun onOpen(conn: WebSocket?, handshake: ClientHandshake?) {
        Log.d(TAG, "Client connected: ${conn?.remoteSocketAddress}")
        if (currentClient?.isOpen == true) {
            Log.w(TAG, "Rejecting second client")
            conn?.close(1008, "Only one viewer allowed at a time")
            return
        }
        currentClient = conn
    }

    override fun onClose(conn: WebSocket?, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected code=$code reason='$reason' remote=$remote")
        if (conn === currentClient) {
            currentClient = null
        }
    }

    override fun onMessage(conn: WebSocket?, message: String?) {
        if (message == null || conn == null) return
        Log.d(TAG, "RX ← $message")

        scope.launch {
            try {
                val json = JSONObject(message)
                when (val type = json.optString("type")) {
                    "offer" -> {
                        val sdp = json.getString("sdp")
                        onOffer(sdp)
                    }
                    "candidate" -> {
                        val c = json.getJSONObject("candidate")
                        onIceCandidate(
                            c.optString("sdpMid", ""),
                            c.optInt("sdpMLineIndex", 0),
                            c.getString("candidate")
                        )
                    }
                    "ping" -> sendToClient(MSG_PONG)
                    else -> Log.w(TAG, "Unknown message type: $type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing message", e)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        Log.e(TAG, "WebSocket error (conn=${conn?.remoteSocketAddress})", ex)
    }

    override fun onStart() {
        Log.i(TAG, "Signaling server listening on port ${address.port}")
        connectionLostTimeout = 30  // seconds
    }

    // -------------------------------------------------------------------------
    // Outbound helpers
    // -------------------------------------------------------------------------

    /** Send the local SDP answer to the connected viewer. */
    fun sendAnswer(sdp: String) {
        val msg = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        }.toString()
        Log.d(TAG, "TX → answer")
        sendToClient(msg)
    }

    /** Forward a local ICE candidate to the connected viewer. */
    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, sdp: String) {
        val msg = JSONObject().apply {
            put("type", "candidate")
            put("candidate", JSONObject().apply {
                put("sdpMid", sdpMid)
                put("sdpMLineIndex", sdpMLineIndex)
                put("candidate", sdp)
            })
        }.toString()
        Log.d(TAG, "TX → candidate")
        sendToClient(msg)
    }

    private fun sendToClient(message: String) {
        val client = currentClient ?: return
        if (client.isOpen) {
            client.send(message)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Gracefully stop the server (waits up to [timeoutMs] ms). */
    fun stopServer(timeoutMs: Int = 1000) {
        try {
            stop(timeoutMs)
            Log.d(TAG, "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }
}
