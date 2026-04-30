package com.example.cameraapp.signaling

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket signaling client for connecting to an external signaling server.
 *
 * Protocol (JSON messages):
 *
 *   Client → Server:
 *     { "type": "offer",     "sdp": "<SDP string>" }
 *     { "type": "answer",    "sdp": "<SDP string>" }
 *     { "type": "candidate", "candidate": { "sdpMid": "…", "sdpMLineIndex": 0, "candidate": "…" } }
 *     { "type": "ping" }
 *
 *   Server → Client:
 *     { "type": "offer",     "sdp": "<SDP string>" }
 *     { "type": "answer",    "sdp": "<SDP string>" }
 *     { "type": "candidate", "candidate": { … } }
 *     { "type": "pong" }
 *
 * Supports automatic reconnection with exponential backoff.
 */
class SignalingClient(
    private val serverUrl: String,
    private val onOffer: (sdp: String) -> Unit,
    private val onAnswer: (sdp: String) -> Unit,
    private val onIceCandidate: (sdpMid: String, sdpMLineIndex: Int, sdp: String) -> Unit
) {

    companion object {
        private const val TAG = "SignalingClient"
        private const val NORMAL_CLOSURE_STATUS = 1000
        private const val RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val PING_INTERVAL_MS = 30000L
        private const val MSG_PING = """{"type":"ping"}"""
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // No timeout for WebSocket reads
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var shouldReconnect = false

    @Volatile
    private var reconnectDelayMs = RECONNECT_DELAY_MS

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    /**
     * Connect to the signaling server.
     * Enables automatic reconnection on failure.
     */
    fun connect() {
        Log.d(TAG, "Connecting to $serverUrl")
        shouldReconnect = true
        reconnectDelayMs = RECONNECT_DELAY_MS
        performConnect()
    }

    private fun performConnect() {
        if (webSocket != null) {
            Log.w(TAG, "Already connected or connecting")
            return
        }

        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, webSocketListener)
    }

    /**
     * Disconnect from the signaling server.
     * Disables automatic reconnection.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        shouldReconnect = false
        webSocket?.close(NORMAL_CLOSURE_STATUS, "Client disconnecting")
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        scope.launch {
            Log.d(TAG, "Reconnecting in ${reconnectDelayMs}ms")
            delay(reconnectDelayMs)

            if (shouldReconnect && webSocket == null) {
                // Exponential backoff with cap
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                performConnect()
            }
        }
    }

    // -------------------------------------------------------------------------
    // WebSocket listener
    // -------------------------------------------------------------------------

    private val webSocketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket connected")
            _connectionState.value = ConnectionState.Connected
            reconnectDelayMs = RECONNECT_DELAY_MS  // Reset backoff on successful connection
            startPingLoop()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "RX ← $text")
            scope.launch {
                try {
                    val json = JSONObject(text)
                    when (val type = json.optString("type")) {
                        "offer" -> {
                            val sdp = json.getString("sdp")
                            onOffer(sdp)
                        }
                        "answer" -> {
                            val sdp = json.getString("sdp")
                            onAnswer(sdp)
                        }
                        "candidate" -> {
                            val c = json.getJSONObject("candidate")
                            onIceCandidate(
                                c.optString("sdpMid", ""),
                                c.optInt("sdpMLineIndex", 0),
                                c.getString("candidate")
                            )
                        }
                        "pong" -> {
                            // Server acknowledged our ping
                            Log.d(TAG, "Received pong")
                        }
                        else -> Log.w(TAG, "Unknown message type: $type")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message", e)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: code=$code reason=$reason")
            webSocket.close(NORMAL_CLOSURE_STATUS, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
            this@SignalingClient.webSocket = null
            _connectionState.value = ConnectionState.Disconnected
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket error: ${response?.code}", t)
            this@SignalingClient.webSocket = null
            _connectionState.value = ConnectionState.Disconnected
            scheduleReconnect()
        }
    }

    // -------------------------------------------------------------------------
    // Outbound helpers
    // -------------------------------------------------------------------------

    /**
     * Send an SDP offer to the signaling server.
     */
    fun sendOffer(sdp: String) {
        val msg = JSONObject().apply {
            put("type", "offer")
            put("sdp", sdp)
        }.toString()
        Log.d(TAG, "TX → offer")
        sendMessage(msg)
    }

    /**
     * Send an SDP answer to the signaling server.
     */
    fun sendAnswer(sdp: String) {
        val msg = JSONObject().apply {
            put("type", "answer")
            put("sdp", sdp)
        }.toString()
        Log.d(TAG, "TX → answer")
        sendMessage(msg)
    }

    /**
     * Send an ICE candidate to the signaling server.
     */
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
        sendMessage(msg)
    }

    private fun sendMessage(message: String) {
        val ws = webSocket
        if (ws == null) {
            Log.w(TAG, "Cannot send message: not connected")
            return
        }
        try {
            ws.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
        }
    }

    // -------------------------------------------------------------------------
    // Keep-alive
    // -------------------------------------------------------------------------

    private fun startPingLoop() {
        scope.launch {
            while (webSocket != null && _connectionState.value == ConnectionState.Connected) {
                delay(PING_INTERVAL_MS)
                webSocket?.send(MSG_PING)
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
