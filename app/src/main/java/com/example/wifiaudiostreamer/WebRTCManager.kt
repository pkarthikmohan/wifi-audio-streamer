package com.example.wifiaudiostreamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hybrid Streamer: Supports both RAW TCP (App) and HTTP WAV (Browser).
 * - Port 50005: Raw PCM (for Android App Clients) - Low Latency
 * - Port 8080:  WAV/HTTP (for Web Browsers) - Compatible
 */
class WebRTCManager(
    private val context: Context,
    private val onStatus: (String) -> Unit
) {

    private var isStreaming = AtomicBoolean(false)
    private var streamScope = CoroutineScope(Dispatchers.IO)
    
    // Audio Config: 44.1kHz Stereo 16-bit
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_STEREO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    private val MIN_BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG_IN, AUDIO_FORMAT)
    private val CHUNK_SIZE = 1024 // 1KB chunks

    // Ports
    private val APP_PORT = 50005
    private val HTTP_PORT = 8080

    // Sockets
    private var appServerSocket: ServerSocket? = null
    private var httpServer: ServerSocket? = null
    private var clientSocket: Socket? = null // For Listener Mode (Client side)

    // Active Host Clients (Both App and Browser)
    private val clientStreams = CopyOnWriteArrayList<OutputStream>()
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // ================= HOST LOGIC =================

    fun startHost(projection: MediaProjection?) {
        streamScope.launch {
            try {
                close()
                isStreaming.set(true)
                onStatus("Host: Starting Servers...")

                // 1. Start App Server (Raw TCP)
                launch(Dispatchers.IO) {
                    runAppServer()
                }

                // 2. Start HTTP Server (Browser)
                launch(Dispatchers.IO) {
                    runHttpServer()
                }

                // 3. Start Audio Capture & Broadcasting
                startAudioCapture(projection)

            } catch (e: Exception) {
                onStatus("Host Error: ${e.message}")
            }
        }
    }

    private fun runAppServer() {
        try {
            appServerSocket = ServerSocket()
            appServerSocket?.reuseAddress = true
            appServerSocket?.bind(java.net.InetSocketAddress(APP_PORT))
            
            onStatus("Host: App Server running on port $APP_PORT")
            
            while (isStreaming.get()) {
                val client = appServerSocket?.accept()
                client?.let {
                    it.tcpNoDelay = true
                    onStatus("New App Client: ${it.inetAddress.hostAddress}")
                    synchronized(clientStreams) {
                        clientStreams.add(it.getOutputStream())
                    }
                }
            }
        } catch (e: Exception) {
            if (isStreaming.get()) onStatus("App Server Stopped: ${e.message}")
        }
    }

    private fun runHttpServer() {
        try {
            httpServer = ServerSocket()
            httpServer?.reuseAddress = true
            httpServer?.bind(java.net.InetSocketAddress(HTTP_PORT))
            
            onStatus("Host: Web Server running on port $HTTP_PORT")
            onStatus("Web: Open http://<IP>:8080")

            while (isStreaming.get()) {
                val client = httpServer?.accept()
                client?.let {
                    streamScope.launch(Dispatchers.IO) { handleHttpClient(it) }
                }
            }
        } catch (e: Exception) {
             if (isStreaming.get()) onStatus("Web Server Stopped: ${e.message}")
        }
    }

    private fun handleHttpClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            // Read HTTP Request (Ignore content, just consume headers roughly)
            val buffer = ByteArray(1024)
            input.read(buffer) // Consume GET request
            
            // Send Headers
            val headers = "HTTP/1.1 200 OK\r\n" +
                          "Content-Type: audio/wav\r\n" +
                          "Connection: keep-alive\r\n" +
                          "Cache-Control: no-cache\r\n" +
                          "\r\n"
            output.write(headers.toByteArray())

            // Send WAV Header (Infinite Length)
            val wavHeader = createWavHeader(SAMPLE_RATE, 2, 16)
            output.write(wavHeader)
            output.flush()

            onStatus("New Web Client: ${socket.inetAddress.hostAddress}")
            
            // Add to broadcast list
            clientStreams.add(output)
            
            // Note: If client disconnects, the write loop in startAudioCapture will catch it
        } catch (e: Exception) {
            socket.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(projection: MediaProjection?) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            if (projection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                
                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG_IN)
                        .build())
                    .setBufferSizeInBytes(MIN_BUFFER_SIZE)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
            } else {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_IN,
                    AUDIO_FORMAT,
                    MIN_BUFFER_SIZE
                )
            }

            audioRecord?.startRecording()
            val buffer = ByteArray(CHUNK_SIZE)

            while (isStreaming.get()) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: 0
                if (read > 0) {
                    // Broadcast to ALL connected clients
                    val iter = clientStreams.iterator()
                    while (iter.hasNext()) {
                        val stream = iter.next()
                        try {
                            stream.write(buffer, 0, read)
                        } catch (e: Exception) {
                            // Client disconnected
                            clientStreams.remove(stream)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            onStatus("Capture Error: ${e.message}")
        } finally {
            close()
        }
    }


    // ================= LISTENER LOGIC (APP CLIENT) =================

    fun startListener(hostIp: String) {
        streamScope.launch {
            try {
                close()
                onStatus("Listener: Connecting to $hostIp:$APP_PORT...")
                clientSocket = Socket(hostIp, APP_PORT)
                clientSocket?.tcpNoDelay = true 
                
                onStatus("Listener: Connected! Buffering...")
                isStreaming.set(true)
                startAudioPlayback(clientSocket!!.getInputStream())
            } catch (e: Exception) {
                onStatus("Connection Error: ${e.message}")
            }
        }
    }

    private fun startAudioPlayback(input: InputStream) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG_OUT)
                    .build())
                .setBufferSizeInBytes(MIN_BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            audioTrack?.play()
            
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            
            while (isStreaming.get()) {
                bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                audioTrack?.write(buffer, 0, bytesRead)
            }
        } catch (e: Exception) {
            onStatus("Playback Error: ${e.message}")
        } finally {
            close()
        }
    }

    // ================= UTILS =================

    fun stop() {
        isStreaming.set(false)
        close()
        onStatus("Stopped.")
    }

    private fun close() {
        try {
            // Close all client streams
            for (stream in clientStreams) {
                try { stream.close() } catch (e: Exception) {}
            }
            clientStreams.clear()
        
            audioRecord?.stop(); audioRecord?.release()
            audioTrack?.stop(); audioTrack?.release()
            
            appServerSocket?.close()
            httpServer?.close()
            clientSocket?.close()
        } catch (e: Exception) {}
        
        audioRecord = null
        audioTrack = null
        appServerSocket = null
        httpServer = null
        clientSocket = null
    }

    /**
     * Creates a standard WAV header for PCM data.
     * Sets data size to Max Int to support "streaming".
     */
    private fun createWavHeader(sampleRate: Int, channels: Short, bitDepth: Short): ByteArray {
        val totalDataLen = Int.MAX_VALUE - 44 // Fake infinite length
        val byteRate = sampleRate * channels * (bitDepth / 8)
        
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen + 36) // ChunkSize
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size (PCM)
        buffer.putShort(1) // AudioFormat (1=PCM)
        buffer.putShort(channels)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * (bitDepth / 8)).toShort()) // BlockAlign
        buffer.putShort(bitDepth)
        buffer.put("data".toByteArray())
        buffer.putInt(totalDataLen) // Subchunk2Size
        
        return header
    }
}
