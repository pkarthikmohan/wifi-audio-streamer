# Wifi Audio Streamer (Android)

An Android application that captures audio and streams it in real-time over local Wi-Fi to multiple listener devices using WebRTC and Opus.

## Features
- **Host Mode**: Captures audio and acts as a signaling server.
- **Listener Mode**: Connects to the host and plays received audio.
- **Low Latency**: Uses WebRTC (UDP/TCP) with Opus codec.
- **Local Wi-Fi**: No internet server required; uses direct P2P connection signaled over local TCP.

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog or newer.
- Android SDK API 34.
- Two Android devices connected to the **same Wi-Fi network**.

### Building
1. Open the project in Android Studio.
2. Sync Gradle files.
3. Keep the default configuration or update `minSdk` in `app/build.gradle.kts` if needed (Default: 29).

### Usage
1. **Device A (Host)**:
   - Select "Host" mode.
   - Grant Microphone and Network permissions.
   - Note the **Local IP** displayed on the screen.
   - Click **Start Streaming**.
   - *Note: In this prototype, audio is captured via the Microphone. Place the device near the audio source.*

2. **Device B (Listener)**:
   - Select "Listener" mode.
   - Enter the **Host IP** from Device A.
   - Click **Connect**.
   - The status log should show "Connected" -> "Received Offer" -> "Received Audio Stream".

## Architecture Details
- **Signaling**: Implemented as a simple TCP Socket server running on the Host (Port 8080).
- **WebRTC**: Uses `google-webrtc` library.
- **Audio Capture**: 
  - Simply uses `JavaAudioDeviceModule` (Microphone source) for broad compatibility.
  - *Advanced:* To capture internal system audio (Android 10+), the app uses the `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission. A full implementation would require passing a `MediaProjection` token to a custom `AudioRecord` implementation fed into WebRTC.

## Troubleshooting
- **No Audio?** Check volume on the Listener device. Ensure Host permissions are granted.
- **Connection Failed?** Ensure both devices are on the SAME Wi-Fi subnet. Some corporate/public Wi-Fi networks block P2P traffic.
