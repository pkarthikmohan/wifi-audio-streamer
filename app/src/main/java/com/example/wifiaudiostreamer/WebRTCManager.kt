package com.example.wifiaudiostreamer

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hybrid Audio Streamer — Renamed from WebRTCManager (no WebRTC is used).
 *
 * Runs two parallel servers:
 *  - Port 50005 (Raw TCP): Ultra-low-latency PCM for Android listener app.
 *  - Port 8080  (HTTP WAV): Infinite WAV stream compatible with any web browser.
 *
 * Audio config: 44.1 kHz · Stereo · 16-bit PCM
 * CHUNK_SIZE is intentionally kept at 1 KB — do NOT increase, it adds latency.
 */
class AudioStreamManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onClientCountChanged: ((Int) -> Unit)? = null
) {

    private val isStreaming = AtomicBoolean(false)
    private var streamScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Audio Config ────────────────────────────────────────────────────────
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG_IN  = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
    private val CHUNK_SIZE = 1024  // 1 KB — keep small for low latency

    // ── Ports ────────────────────────────────────────────────────────────────
    private val APP_PORT  = 50005
    private val HTTP_PORT = 8080

    // ── Sockets ──────────────────────────────────────────────────────────────
    private var appServerSocket: ServerSocket? = null
    private var httpServer: ServerSocket? = null
    private var clientSocket: Socket? = null   // Listener mode only

    // ── Broadcast list (shared by both App and Browser clients) ──────────────
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()
    private var clientCount = 0

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // ════════════════════════════════════════════════════════════════════════
    //  HOST
    // ════════════════════════════════════════════════════════════════════════

    fun startHost(projection: MediaProjection?) {
        streamScope.launch {
            try {
                close()
                isStreaming.set(true)
                onStatus("Host: Starting servers…")

                launch(Dispatchers.IO) { runAppServer() }
                launch(Dispatchers.IO) { runHttpServer() }
                startAudioCapture(projection)

            } catch (e: Exception) {
                onStatus("Host Error: ${e.message}")
            }
        }
    }

    /** Accepts raw-TCP connections from Android listener apps. */
    private fun runAppServer() {
        try {
            appServerSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(APP_PORT))
            }
            onStatus("Host: App server ready on port $APP_PORT")

            while (isStreaming.get()) {
                val client = appServerSocket?.accept() ?: continue
                client.tcpNoDelay = true   // Nagle OFF — critical for low latency
                onStatus("App client connected: ${client.inetAddress.hostAddress}")
                clientStreams.add(client.getOutputStream())
                clientCount++
                onClientCountChanged?.invoke(clientCount)
            }
        } catch (e: Exception) {
            if (isStreaming.get()) onStatus("App server stopped: ${e.message}")
        }
    }

    /** Accepts HTTP connections from web browsers. */
    private fun runHttpServer() {
        try {
            httpServer = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(HTTP_PORT))
            }
            onStatus("Host: Web server ready on port $HTTP_PORT")
            onStatus("Browser → http://<IP>:8080")

            while (isStreaming.get()) {
                val client = httpServer?.accept() ?: continue
                streamScope.launch(Dispatchers.IO) { handleHttpClient(client) }
            }
        } catch (e: Exception) {
            if (isStreaming.get()) onStatus("Web server stopped: ${e.message}")
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input  = socket.getInputStream()
            val output = socket.getOutputStream()

            // Consume HTTP request headers
            val buf = ByteArray(4096)
            var total = 0
            while (total < buf.size) {
                val n = input.read(buf, total, buf.size - total)
                if (n == -1) break
                total += n
                if (String(buf, 0, total).contains("\r\n\r\n")) break
            }

            val requestStr = String(buf, 0, total)
            val firstLine = requestStr.lines().firstOrNull() ?: ""
            val isStreamRequest = firstLine.contains("/stream") ||
                    requestStr.contains("Accept: audio", ignoreCase = true) ||
                    requestStr.contains("Range:", ignoreCase = true)

            if (!isStreamRequest && (firstLine.contains("GET / ") || firstLine.contains("GET /index.html") || firstLine.contains("GET /?"))) {
                // Serve interactive HTML5 Web Player with Low-Latency / Stable Mode
                val html = getWebPlayerHtml()
                val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" + html
                output.write(response.toByteArray(Charsets.UTF_8))
                output.flush()
                socket.close()
                return
            }

            // Stream audio response
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                 "Content-Type: audio/wav\r\n" +
                 "Connection: keep-alive\r\n" +
                 "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                 "Pragma: no-cache\r\n" +
                 "Expires: 0\r\n" +
                 "Access-Control-Allow-Origin: *\r\n" +
                 "\r\n").toByteArray()
            )
            // WAV header with fake-infinite length so browsers treat it as a continuous stream
            output.write(createWavHeader(SAMPLE_RATE, 2, 16))
            output.flush()

            onStatus("Browser client connected: ${socket.inetAddress.hostAddress}")
            clientStreams.add(output)
            clientCount++
            onClientCountChanged?.invoke(clientCount)

        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun getWebPlayerHtml(): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>WiFi Audio Streamer</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
        body { background: #0A0A14; color: #E8E6FF; display: flex; justify-content: center; align-items: center; min-height: 100vh; padding: 20px; }
        .card { background: #131224; border: 1px solid #2E2C5A; border-radius: 24px; padding: 32px; max-width: 440px; width: 100%; box-shadow: 0 20px 40px rgba(0,0,0,0.6); text-align: center; }
        .badge { display: inline-flex; align-items: center; gap: 8px; padding: 6px 14px; background: rgba(57, 229, 160, 0.12); border: 1px solid rgba(57, 229, 160, 0.3); border-radius: 20px; color: #39E5A0; font-size: 12px; font-weight: bold; letter-spacing: 1px; margin-bottom: 20px; }
        .dot { width: 8px; height: 8px; border-radius: 50%; background: #39E5A0; }
        .pulse { animation: pulse 1.5s infinite; }
        @keyframes pulse { 0% { box-shadow: 0 0 0 0 rgba(57, 229, 160, 0.7); } 70% { box-shadow: 0 0 0 10px rgba(57, 229, 160, 0); } 100% { box-shadow: 0 0 0 0 rgba(57, 229, 160, 0); } }
        h1 { font-size: 22px; font-weight: 800; letter-spacing: 0.5px; margin-bottom: 6px; }
        .subtitle { color: #8A88B0; font-size: 13px; margin-bottom: 24px; }
        .mode-toggle { background: #1C1B35; border-radius: 14px; padding: 4px; display: flex; gap: 4px; margin-bottom: 20px; }
        .mode-btn { flex: 1; padding: 12px 10px; border: none; border-radius: 10px; background: transparent; color: #8A88B0; font-weight: bold; font-size: 13px; cursor: pointer; transition: all 0.2s; }
        .mode-btn.active { background: linear-gradient(135deg, #7C5FFF, #00D4FF); color: #FFF; box-shadow: 0 4px 12px rgba(124, 95, 255, 0.3); }
        .mode-desc { font-size: 12px; color: #8A88B0; margin-bottom: 24px; min-height: 36px; line-height: 1.4; }
        .play-btn { width: 100%; padding: 16px; border: none; border-radius: 16px; font-size: 16px; font-weight: bold; cursor: pointer; background: linear-gradient(135deg, #7C5FFF, #00D4FF); color: #FFF; letter-spacing: 0.8px; transition: transform 0.1s, opacity 0.2s; }
        .play-btn:active { transform: scale(0.98); }
        .play-btn.stop { background: linear-gradient(135deg, #FF4D6B, #FF7A00); }
        .status-box { margin-top: 20px; font-size: 12px; color: #8A88B0; font-family: monospace; }
        audio { display: none; }
    </style>
</head>
<body>
    <div class="card">
        <div class="badge"><div class="dot pulse"></div>RECEIVER READY</div>
        <h1>WiFi Audio Streamer</h1>
        <div class="subtitle">Select your playback mode below</div>

        <div class="mode-toggle">
            <button id="btnLowLatency" class="mode-btn active" onclick="setMode('low')">⚡ Low Latency</button>
            <button id="btnStable" class="mode-btn" onclick="setMode('stable')">🛡️ Smooth</button>
        </div>

        <div id="modeDesc" class="mode-desc">
            Ultra-fast real-time audio (~200ms). Perfect for videos, movies, and sync.
        </div>

        <button id="btnPlay" class="play-btn" onclick="togglePlay()">START LISTENING</button>
        <div id="statusText" class="status-box">Status: Idle</div>

        <audio id="audioEl" preload="none"></audio>
    </div>

    <script>
        let currentMode = 'low';
        let isPlaying = false;
        let audioCtx = null;
        let abortController = null;
        let nextStartTime = 0;
        const audioEl = document.getElementById('audioEl');

        function setMode(mode) {
            if (isPlaying) togglePlay(); // stop before switching
            currentMode = mode;
            document.getElementById('btnLowLatency').className = 'mode-btn' + (mode === 'low' ? ' active' : '');
            document.getElementById('btnStable').className = 'mode-btn' + (mode === 'stable' ? ' active' : '');
            document.getElementById('modeDesc').innerText = mode === 'low'
                ? "Ultra-fast real-time audio (~200ms). Perfect for videos, movies, and sync."
                : "Smooth buffered audio (~2-3s). Ideal for music & weak Wi-Fi without glitches.";
        }

        async function togglePlay() {
            if (isPlaying) {
                stopAudio();
            } else {
                startAudio();
            }
        }

        function startAudio() {
            isPlaying = true;
            document.getElementById('btnPlay').innerText = "STOP LISTENING";
            document.getElementById('btnPlay').className = "play-btn stop";
            document.getElementById('statusText').innerText = "Status: Connecting...";

            if (currentMode === 'stable') {
                audioEl.src = "/stream?t=" + Date.now();
                audioEl.play().then(() => {
                    document.getElementById('statusText').innerText = "Status: Playing (Smooth Mode)";
                }).catch(err => {
                    document.getElementById('statusText').innerText = "Error: " + err.message;
                    stopAudio();
                });
            } else {
                startLowLatencyStream();
            }
        }

        function stopAudio() {
            isPlaying = false;
            document.getElementById('btnPlay').innerText = "START LISTENING";
            document.getElementById('btnPlay').className = "play-btn";
            document.getElementById('statusText').innerText = "Status: Stopped";

            if (audioEl) {
                audioEl.pause();
                audioEl.removeAttribute('src');
                audioEl.load();
            }
            if (abortController) {
                abortController.abort();
                abortController = null;
            }
            if (audioCtx) {
                audioCtx.close();
                audioCtx = null;
            }
            nextStartTime = 0;
        }

        async function startLowLatencyStream() {
            try {
                audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 44100 });
                if (audioCtx.state === 'suspended') await audioCtx.resume();

                abortController = new AbortController();
                const response = await fetch('/stream?t=' + Date.now(), { signal: abortController.signal });
                const reader = response.body.getReader();
                let headerSkipped = false;
                let residual = new Uint8Array(0);

                document.getElementById('statusText').innerText = "Status: Streaming (Low Latency)";
                nextStartTime = audioCtx.currentTime + 0.05;

                while (isPlaying) {
                    const { done, value } = await reader.read();
                    if (done) break;

                    let data = value;
                    if (!headerSkipped) {
                        if (data.length <= 44) continue;
                        data = data.slice(44); // Skip 44-byte WAV header
                        headerSkipped = true;
                    }

                    // Combine residual from previous chunk
                    const combined = new Uint8Array(residual.length + data.length);
                    combined.set(residual);
                    combined.set(data, residual.length);

                    // 16-bit stereo = 4 bytes per frame
                    const numFrames = Math.floor(combined.length / 4);
                    if (numFrames === 0) {
                        residual = combined;
                        continue;
                    }

                    const playableBytes = numFrames * 4;
                    const pcmData = combined.subarray(0, playableBytes);
                    residual = combined.slice(playableBytes);

                    const int16 = new Int16Array(pcmData.buffer, pcmData.byteOffset, numFrames * 2);
                    const audioBuffer = audioCtx.createBuffer(2, numFrames, 44100);
                    const left = audioBuffer.getChannelData(0);
                    const right = audioBuffer.getChannelData(1);

                    for (let i = 0; i < numFrames; i++) {
                        left[i]  = int16[i * 2]     / 32768.0;
                        right[i] = int16[i * 2 + 1] / 32768.0;
                    }

                    const source = audioCtx.createBufferSource();
                    source.buffer = audioBuffer;
                    source.connect(audioCtx.destination);

                    if (nextStartTime < audioCtx.currentTime) {
                        nextStartTime = audioCtx.currentTime + 0.02;
                    }
                    source.start(nextStartTime);
                    nextStartTime += audioBuffer.duration;
                }
            } catch (err) {
                if (isPlaying) {
                    document.getElementById('statusText').innerText = "Stream ended / error: " + err.message;
                    stopAudio();
                }
            }
        }
    </script>
</body>
</html>
        """.trimIndent()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(projection: MediaProjection?) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            audioRecord = if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Internal system audio via MediaProjection
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG_IN)
                            .build()
                    )
                    .setBufferSizeInBytes(MIN_BUFFER_SIZE)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                // Microphone — try stereo, fall back to mono for device compatibility
                tryCreateMicRecord()
            }

            audioRecord?.startRecording()
            val buffer = ByteArray(CHUNK_SIZE)

            while (isStreaming.get()) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                if (read > 0) broadcastAudio(buffer, read)
            }
        } catch (e: Exception) {
            onStatus("Capture error: ${e.message}")
        } finally {
            close()
        }
    }

    /**
     * Tries stereo mic first; silently falls back to mono if the device doesn't support it.
     * Mono fallback does NOT affect latency or sync — only channel count changes.
     */
    @SuppressLint("MissingPermission")
    private fun tryCreateMicRecord(): AudioRecord {
        return try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT, MIN_BUFFER_SIZE
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) rec
            else { rec.release(); createMonoMicRecord() }
        } catch (_: Exception) {
            onStatus("Stereo mic unavailable — falling back to mono")
            createMonoMicRecord()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMonoMicRecord(): AudioRecord {
        val monoBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT
        )
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AUDIO_FORMAT, monoBuffer
        )
    }

    /**
     * Broadcasts one audio chunk to every connected client.
     * Dead clients are detected on write failure and immediately cleaned up.
     */
    private fun broadcastAudio(buffer: ByteArray, length: Int) {
        val dead = mutableListOf<OutputStream>()
        for (stream in clientStreams) {
            try {
                stream.write(buffer, 0, length)
            } catch (_: Exception) {
                dead.add(stream)
            }
        }
        if (dead.isNotEmpty()) {
            for (d in dead) {
                clientStreams.remove(d)
                try { d.close() } catch (_: Exception) {}
            }
            clientCount = maxOf(0, clientCount - dead.size)
            onClientCountChanged?.invoke(clientCount)
            onStatus("${dead.size} client(s) disconnected.")
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  LISTENER (Android app client side)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Connects to a host and plays audio.
     * Automatically reconnects if the connection drops (up to 10 retries, 3 s apart).
     * tcpNoDelay and PERFORMANCE_MODE_LOW_LATENCY are kept throughout — no latency impact.
     */
    fun startListener(hostIp: String) {
        isStreaming.set(true)
        streamScope.launch {
            var attempt = 0
            val maxRetries = 10

            while (isStreaming.get() && attempt <= maxRetries) {
                if (attempt > 0) {
                    onStatus("Reconnecting in 3 s… (attempt $attempt/$maxRetries)")
                    delay(3_000)
                    if (!isStreaming.get()) break
                }
                try {
                    onStatus("Connecting to $hostIp:$APP_PORT…")
                    val socket = Socket(hostIp, APP_PORT).also {
                        it.tcpNoDelay = true   // Keep Nagle OFF for low latency
                    }
                    clientSocket = socket
                    onStatus("Connected — playing audio…")
                    attempt = 0   // Reset on successful connection
                    startAudioPlayback(socket.getInputStream())
                    // Reach here when stream ends naturally (host closed)
                    if (isStreaming.get()) {
                        onStatus("Host stream ended.")
                        attempt++
                    }
                } catch (e: Exception) {
                    if (!isStreaming.get()) break
                    onStatus("Connection failed: ${e.message}")
                    attempt++
                }
            }
            if (attempt > maxRetries) onStatus("Max retries reached. Stopped.")
        }
    }

    private fun startAudioPlayback(input: InputStream) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(MIN_BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)  // Keep — critical for A/V sync
                .build()

            audioTrack?.play()
            val buffer = ByteArray(CHUNK_SIZE)

            while (isStreaming.get()) {
                val n = input.read(buffer)
                if (n == -1) break
                audioTrack?.write(buffer, 0, n)
            }
        } catch (e: Exception) {
            if (isStreaming.get()) onStatus("Playback error: ${e.message}")
        } finally {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  CONTROL & UTILS
    // ════════════════════════════════════════════════════════════════════════

    fun stop() {
        isStreaming.set(false)
        close()
        onStatus("Stopped.")
    }

    private fun close() {
        try {
            for (s in clientStreams) try { s.close() } catch (_: Exception) {}
            clientStreams.clear()
            clientCount = 0
            onClientCountChanged?.invoke(0)

            audioRecord?.stop(); audioRecord?.release()
            audioTrack?.stop();  audioTrack?.release()

            appServerSocket?.close()
            httpServer?.close()
            clientSocket?.close()
        } catch (_: Exception) {}

        audioRecord    = null
        audioTrack     = null
        appServerSocket = null
        httpServer     = null
        clientSocket   = null
    }

    /**
     * Builds a 44-byte WAV header.
     * Data length is set to Int.MAX_VALUE so browsers see an "infinite" stream.
     */
    private fun createWavHeader(sampleRate: Int, channels: Short, bitDepth: Short): ByteArray {
        val dataLen  = Int.MAX_VALUE - 44
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val header   = ByteArray(44)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(dataLen + 36)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels)
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * (bitDepth / 8)).toShort())
            putShort(bitDepth)
            put("data".toByteArray())
            putInt(dataLen)
        }
        return header
    }
}
