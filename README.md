# VigilNode

A production-ready Android surveillance camera app that streams video and audio over WebRTC on a private network (Tailscale / LAN) — **no cloud backend, no external signaling server**.

## Features

| Feature | Implementation |
|---------|----------------|
| Video capture | CameraX (H264 via WebRTC encoder) |
| Audio capture | WebRTC built-in audio engine (Opus) |
| Two-way audio (talkback) | WebRTC receive audio track |
| Signaling | Built-in WebSocket server on port **8080** |
| Local recording | CameraX `VideoCapture<Recorder>`, 5-min circular segments |
| Storage management | Auto-deletes oldest segments when > 500 MB |
| Background operation | Android Foreground Service (LifecycleService) |
| Network | Tailscale / LAN (no NAT traversal required) |

## Project Structure

```
app/src/main/java/com/example/cameraapp/
├── camera/
│   ├── CameraController.kt   – CameraX: Preview, ImageAnalysis, VideoCapture
│   └── AudioController.kt    – AudioManager routing & focus
├── webrtc/
│   ├── WebRTCManager.kt      – PeerConnection, SDP/ICE handling
│   ├── CameraXVideoCapturer.kt – bridges CameraX frames → WebRTC pipeline
│   └── SimpleSdpObserver.kt  – SdpObserver convenience base class
├── signaling/
│   └── SignalingServer.kt    – Embedded WebSocket server (Java-WebSocket)
├── service/
│   └── CameraService.kt      – Foreground LifecycleService (wires all components)
├── recording/
│   └── RecordingManager.kt   – Circular-storage segment recording
└── MainActivity.kt           – UI: preview, status, permission handling
```

## How to Build

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer  
- JDK 17  
- Android SDK with API 34  

### Steps

1. **Get the Gradle wrapper JAR** (one-time setup – not committed to version control):
   ```bash
   gradle wrapper --gradle-version 8.4
   ```
   Or simply open the project in Android Studio – it will download Gradle automatically.

2. **Build a debug APK**:
   ```bash
   ./gradlew assembleDebug
   ```

3. **Install on a connected device**:
   ```bash
   ./gradlew installDebug
   ```

## Connecting a Viewer

1. Ensure the viewer and the Android device are on the same **Tailscale network** (or LAN).
2. Note the device's IP address shown in the app UI.
3. Open the viewer HTML page and connect to:
   ```
   ws://<device-ip>:8080
   ```
4. Send an SDP offer – the device will reply with an SDP answer and ICE candidates.

### Signaling Message Protocol

```jsonc
// Viewer → Device
{ "type": "offer",     "sdp": "<SDP string>" }
{ "type": "candidate", "candidate": { "sdpMid": "…", "sdpMLineIndex": 0, "candidate": "…" } }
{ "type": "ping" }

// Device → Viewer
{ "type": "answer",    "sdp": "<SDP string>" }
{ "type": "candidate", "candidate": { … } }
{ "type": "pong" }
```

## Permissions Required

| Permission | Purpose |
|-----------|---------|
| `CAMERA` | Video capture |
| `RECORD_AUDIO` | Microphone capture (WebRTC) |
| `INTERNET` | WebRTC data channel & signaling |
| `FOREGROUND_SERVICE_CAMERA` | Camera access from service |
| `FOREGROUND_SERVICE_MICROPHONE` | Mic access from service |

## License

See [LICENSE](LICENSE).
