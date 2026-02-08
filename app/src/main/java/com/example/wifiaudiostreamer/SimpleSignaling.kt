package com.example.wifiaudiostreamer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

interface SignalingEvents {
    fun onOfferReceived(sdp: String, socket: Socket)
    fun onAnswerReceived(sdp: String, socket: Socket)
    fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String, socket: Socket)
    fun onClientConnected(socket: Socket)
    fun onClientDisconnected(socket: Socket)
}

class SignalingServer(private val events: SignalingEvents) {
    private var serverSocket: ServerSocket? = null
    private val clients = Collections.synchronizedList(mutableListOf<Socket>())
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start(port: Int = 8080) {
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                println("Server started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    clients.add(client)
                    events.onClientConnected(client)
                    handleClient(client)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    processMessage(line, socket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                clients.remove(socket)
                events.onClientDisconnected(socket)
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
    
    fun send(data: JSONObject, socket: Socket) {
        scope.launch {
            try {
                if (!socket.isClosed) {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(data.toString())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun processMessage(message: String, socket: Socket) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            when (type) {
                "offer" -> events.onOfferReceived(json.getString("sdp"), socket)
                "answer" -> events.onAnswerReceived(json.getString("sdp"), socket)
                "candidate" -> {
                    events.onIceCandidateReceived(
                        json.getString("id"),
                        json.getInt("label"),
                        json.getString("candidate"),
                        socket
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
        clients.forEach { try { it.close() } catch (e: Exception) {} }
        clients.clear()
    }
}


class SignalingClient(private val events: SignalingEvents) {
    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun connect(ip: String, port: Int = 8080) {
        scope.launch {
            try {
                socket = Socket(ip, port)
                // Notify successful connection
                events.onClientConnected(socket!!)
                handleConnection(socket!!)
            } catch (e: Exception) {
                // e.printStackTrace() 
                // We should ideally notify error, but for now lets rely on subsequent failures
            }
        }
    }
    
    fun send(data: JSONObject) {
        scope.launch {
            try {
                socket?.let { s ->
                    if (!s.isClosed) {
                        val writer = PrintWriter(s.getOutputStream(), true)
                        writer.println(data.toString())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (true) {
                val line = reader.readLine() ?: break
                processMessage(line, socket)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun processMessage(message: String, socket: Socket) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type")
            when (type) {
                "offer" -> events.onOfferReceived(json.getString("sdp"), socket)
                "answer" -> events.onAnswerReceived(json.getString("sdp"), socket)
                "candidate" -> {
                    events.onIceCandidateReceived(
                        json.getString("id"),
                        json.getInt("label"),
                        json.getString("candidate"),
                        socket
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun close() {
        try { socket?.close() } catch (e: Exception) {}
    }
}
