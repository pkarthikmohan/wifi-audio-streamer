# Hybrid Wi-Fi Audio Streamer (Android)

A high-performance Android application that streams high-quality audio (Movies, Games, Music) in real-time over local Wi-Fi to other Android devices and Web Browsers.

## Key Features
- **Zero-Lag Android Streaming**: Uses a custom **Raw TCP** socket protocol (~44.1kHz 16-bit PCM) for ultra-low latency playback on the Android "Listener" app.
- **Browser Compatibility**: Parallel **HTTP Server** that keeps a connection open, streaming infinite WAV audio to any web browser (Chrome, Firefox, Safari) without installing an app.
- **Internal System Audio**: Capture internal device audio (requires Android 10+ and Permission) for streaming movies and games.
- **Microphone Support**: Fallback to microphone capture for voice or external sounds.
- **Local Wi-Fi Only**: Fully offline; no internet or external servers required.

## How It Works
The "Host" device runs two parallel servers:
1.  **Port 50005 (Raw TCP)**: Optimized for the Android client app. No buffering, raw PCM data.
2.  **Port 8080 (HTTP)**: Serves a standard WAV header followed by a continuous stream of PCM bytes. Works with `<audio>` tags in browsers.

## Usages
1.  **Silent Movie Night**: Stream your TV/Tablet audio to your phone + headphones so you don't wake the house.
2.  **Multi-Room Music**: Turn old phones into wireless speakers.
3.  **PC/Console Audio**: Use an Android device to capture audio from a specific source.

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog or newer.
- Android SDK API 34.
- Two Android devices OR one Android device + a PC/Laptop on the **same Wi-Fi network**.

### Building
1.  Open the project in Android Studio.
2.  Sync Gradle files.
3.  Build and Run on your device.

## Operating Instructions

### Host Mode (The Streamer)
1.  Open the app and grant Microphone/Notification permissions.
2.  Toggle **"Source: Internal Audio"** if you want to stream system sound (e.g., YouTube, Netflix).
3.  Tap **"Start Host"**.
4.  Note the **IP Address** displayed on the screen (e.g., `192.168.1.50`).

### Listener Mode (Android App)
1.  Open the app on a second device.
2.  Enter the Host's IP Address.
3.  Tap **"Start Listener"**.

### Listener Mode (Web Browser)
1.  Open a web browser on any device (Laptop, iPhone, Smart TV).
2.  Navigate to `http://<HOST_IP>:8080` (e.g., `http://192.168.1.50:8080`).
3.  Audio will start playing automatically.

## Requirements
- **Internal Audio**: Requires Android 10 (API 29) or higher.
- **Network**: 5GHz Wi-Fi is highly recommended for minimal latency.
