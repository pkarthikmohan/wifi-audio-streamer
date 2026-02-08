package com.example.wifiaudiostreamer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.wifiaudiostreamer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var webRTCManager: WebRTCManager? = null
    private var isStreaming = false

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
            val mediaProjection = mediaProjectionManager.getMediaProjection(result.resultCode, result.data!!)
            startHostWithProjection(mediaProjection)
        } else {
             Toast.makeText(this, "Screen Capture Permission Denied", Toast.LENGTH_SHORT).show()
             isStreaming = false
             binding.btnStartHost.text = getString(R.string.start_streaming)
        }
    }

    private val PERMISSIONS = ArrayList<String>().apply {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // WebRTCManager initialized later
        checkPermissions()
        setupUI()
        displayLocalIp()
    }

    private fun setupUI() {
        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbHost) {
                binding.hostInfoLayout.visibility = View.VISIBLE
                binding.listenerInputLayout.visibility = View.GONE
            } else {
                binding.hostInfoLayout.visibility = View.GONE
                binding.listenerInputLayout.visibility = View.VISIBLE
            }
        }

        binding.btnStartHost.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
                binding.btnStartHost.text = getString(R.string.start_streaming)
            } else {
                startHost()
                binding.btnStartHost.text = getString(R.string.stop_streaming)
            }
        }

        binding.btnConnect.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
                binding.btnConnect.text = getString(R.string.connect)
            } else {
                val ip = binding.etHostIp.text.toString()
                if (ip.isNotEmpty()) {
                    startListener(ip)
                    binding.btnConnect.text = getString(R.string.disconnect)
                }
            }
        }
    }

    private fun startHost() {
        if (!hasPermissions()) {
            val missing = PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            Toast.makeText(this, "Missing: ${missing.joinToString()}", Toast.LENGTH_LONG).show()
            checkPermissions()
            return
        }

        // Check if Internal Audio is requested
        if (binding.switchInternalAudio.isChecked) {
            // Internal Audio REQUIRES Foreground Service + MediaProjection
            val serviceIntent = Intent(this, AudioCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Request Projection Permission (Triggers "Start Recording/Casting?" dialog)
            val mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            // Microphone Only - No extra dialog, just start
             isStreaming = true
             startHostWithProjection(null) // null projection = use mic
        }
        
        binding.btnStartHost.isEnabled = false // Debounce
        binding.btnStartHost.postDelayed({ binding.btnStartHost.isEnabled = true }, 1000)
    }
    
    // Called after permission granted or immediately for Mic
    private fun startHostWithProjection(mediaProjection: MediaProjection?) {
        initWebRTC()
        webRTCManager?.startHost(mediaProjection)
        binding.btnStartHost.text = getString(R.string.stop_streaming)
    }

    private fun startListener(ip: String) {
        if (!hasPermissions()) {
            // For listener, mainly INTERNET and maybe RECORD_AUDIO for WebRTC internals
             Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
             checkPermissions()
             return
        }

        initWebRTC()

         // Service needed to keep app alive
        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        webRTCManager?.startListener(ip)
        isStreaming = true
    }

    private fun initWebRTC() {
        if (webRTCManager == null) {
            webRTCManager = WebRTCManager(this) { status ->
                runOnUiThread {
                    binding.tvStatus.append("\n$status")
                    // Auto-scroll
                    val scrollView = binding.tvStatus.parent as? android.widget.ScrollView
                    scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        return PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun stopStreaming() {
        webRTCManager?.stop()
        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        stopService(serviceIntent)
        isStreaming = false
    }

    private fun displayLocalIp() {
        val ipList = mutableListOf<String>()
        
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                // Filter out loopback and likely unnecessary interfaces
                if (intf.isLoopback || !intf.isUp) continue
                
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (inetAddress is java.net.Inet4Address) {
                        val ip = inetAddress.hostAddress
                        // Prioritize probable LAN/Hotspot IPs (192.168.x.x)
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            ipList.add("${intf.displayName}: $ip")
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        
        if (ipList.isEmpty()) {
            binding.tvLocalIp.text = "No IP Found. Check Wifi/Hotspot."
        } else {
            // Show all found IPs to help user pick the right one
            val allIps = ipList.joinToString("\n")
            binding.tvLocalIp.text = "POSSIBLE HOST IPs:\n$allIps\n\nTry in Browser:\nhttp://[IP]:8080"
        }
    }

    private fun checkPermissions() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }
}
