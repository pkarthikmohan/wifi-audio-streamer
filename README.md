# Hybrid Wi-Fi Audio Streamer (Android)

A high-performance Android application that streams high-quality audio (Movies, Games, Music) in real-time over local Wi-Fi to other Android devices and Web Browsers.

## Key Features
- **Zero-Lag Android Streaming**: Uses a custom **Raw TCP** socket protocol (~44.1kHz 16-bit PCM) for ultra-low latency playback on the Android "Listener" app.
- **Dual-Mode Web Receiver**: Built-in HTTP server on port 8080 serving a modern web player with two selectable listening modes:
  - ⚡ **Low Latency Mode (~200ms)**: Uses the **Web Audio API** with chunked PCM streaming for tight lip-sync when watching movies, YouTube, or gaming.
  - 🛡️ **Smooth Mode (~2–3s)**: Uses standard buffered streaming to absorb Wi-Fi jitter and packet drops for uninterrupted music playback.
- **Modern Cyberpunk Dark UI**: Sleek dark theme with live pulsing indicators, instant IP copy, and a dynamic QR code for quick browser connection.
- **Internal System Audio**: Capture internal device audio (requires Android 10+ and Permission) for streaming movies and games.
- **Microphone Support**: Fallback to microphone capture for voice or external sounds.
- **Local Wi-Fi Only**: Fully offline; no internet connection or external servers required.

## How It Works
The "Host" device runs two parallel servers:
1. **Port 50005 (Raw TCP)**: Optimized for the Android client app. No buffering, raw PCM data.
2. **Port 8080 (HTTP)**: Serves an interactive HTML5 web player with dual-mode playback and streams continuous 44.1kHz 16-bit stereo PCM audio.

## Usages
1. **Silent Movie Night**: Stream your TV/Tablet audio to your phone + headphones so you don't wake the house.
2. **Multi-Room Music**: Turn old phones or laptops into wireless speakers.
3. **PC/Console Audio**: Use an Android device to capture audio from a specific source.
4. **Wireless Headphone Alternative**: Listen to your phone audio directly from a laptop browser or smart TV.

## Setup Instructions

### Prerequisites
- Android Studio Hedgehog or newer.
- Android SDK API 34.
- Two Android devices OR one Android device + a PC/Laptop on the **same Wi-Fi network**.

### Building
1. Open the project in Android Studio.
2. Sync Gradle files.
3. Build and Run on your device (`Shift + F10`).

## Operating Instructions

### Host Mode (The Streamer)
1. Open the app and grant Microphone/Notification permissions.
2. Toggle **"Internal Audio"** if you want to stream system sound (e.g., YouTube, Netflix, games).
3. Tap **"START STREAMING"**.
4. Note the **Host IP Address** displayed on the screen (e.g., `192.168.1.50:8080`), or tap **COPY IP**, or have the other device scan the QR code.

### Listener Mode (Android App)
1. Open the app on a second device.
2. Toggle mode to **LISTENER**.
3. Enter the Host's IP Address.
4. Tap **"CONNECT"**.

### Listener Mode (Web Browser)
1. Open any web browser (Chrome, Safari, Edge, Firefox) on a PC, Mac, iPhone, or Smart TV connected to the same Wi-Fi.
2. Navigate to `http://<HOST_IP>:8080` (e.g., `http://192.168.1.50:8080`).
3. Choose your preferred playback mode:
   - **⚡ Low Latency** for movies and gaming.
   - **🛡️ Smooth** for music and stable background listening.
4. Tap **"START LISTENING"**.

## Requirements
- **Internal Audio**: Requires Android 10 (API 29) or higher.
- **Network**: 5GHz Wi-Fi is recommended for the best low-latency performance.

## 🌟 Support & Feedback
- **Found a bug or have a suggestion?** Feel free to open an issue on the [GitHub Issue Tracker](https://github.com/pkarthikmohan/wifi-audio-streamer/issues).
- **Enjoying WiFi Audio Streamer?** If this project helps you, please consider giving it a ⭐ **Star** on GitHub — it means a lot and helps others find it!

## 📄 Version & Author
- **Current Version**: `v1.0.0`
- **Developer**: [Karthik Mohan](https://github.com/pkarthikmohan)

