package com.example.wifiaudiostreamer

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import androidx.appcompat.app.AppCompatDelegate
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.wifiaudiostreamer.databinding.ActivityMainBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var audioStreamManager: AudioStreamManager? = null
    private var isStreaming = false
    private var isHostMode = true
    private var bestIp: String? = null

    private val MAX_LOG_LINES = 50

    // Locks to prevent WiFi/CPU sleep during streaming
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Pulse animation for the LIVE indicator
    private var pulseAnimatorSet: AnimatorSet? = null

    // ── MediaProjection result ────────────────────────────────────────────
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val projection = mgr.getMediaProjection(result.resultCode, result.data!!)
            isStreaming = true
            startHostWithProjection(projection)
        } else {
            Toast.makeText(this, "Screen Capture Permission Denied", Toast.LENGTH_SHORT).show()
            isStreaming = false
            setHostButtonIdle()
        }
    }

    // ── Runtime permissions (CAMERA removed) ─────────────────────────────
    private val PERMISSIONS = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        // Force dark mode — must be before super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()
        setupUI()
        displayLocalIp()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimatorSet?.cancel()
        pulseAnimatorSet = null
        // Guard: binding may not be initialized if onCreate crashed early
        if (::binding.isInitialized) {
            binding.liveIndicator.visibility = View.GONE
        }
        releaseLocks()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UI Setup
    // ═════════════════════════════════════════════════════════════════════

    private fun setupUI() {
        // ── Mode toggle ──────────────────────────────────────────────────
        binding.btnModeHost.setOnClickListener {
            if (!isHostMode) {
                isHostMode = true
                updateModeToggle()
            }
        }
        binding.btnModeListener.setOnClickListener {
            if (isHostMode) {
                isHostMode = false
                updateModeToggle()
            }
        }
        // Start in host mode
        updateModeToggle()

        // ── Host button ──────────────────────────────────────────────────
        binding.btnStartHost.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
                setHostButtonIdle()
            } else {
                startHost()
            }
        }

        // ── Connect button ───────────────────────────────────────────────
        binding.btnConnect.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
                setListenerButtonIdle()
            } else {
                val rawInput = binding.etHostIp.text.toString().trim()
                // Strip 'http://' and any port ':8080' or ':50005' automatically in case pasted
                val ip = rawInput
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore(":")
                    .trim()

                if (ip.isNotEmpty()) {
                    startListener(ip)
                    setListenerButtonActive()
                } else {
                    Toast.makeText(this, "Enter the host IP address", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ── Copy IP ──────────────────────────────────────────────────────
        binding.btnCopyIp.setOnClickListener {
            val ip = bestIp ?: return@setOnClickListener
            val fullAddress = "$ip:8080"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Host IP", fullAddress))
            Toast.makeText(this, "Copied: $fullAddress", Toast.LENGTH_SHORT).show()
        }

        // ── Footer Links (GitHub Star & Issue Tracker) ───────────────────
        binding.btnFooterStar.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pkarthikmohan/wifi-audio-streamer"))
            startActivity(intent)
        }

        binding.btnFooterIssue.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/pkarthikmohan/wifi-audio-streamer/issues"))
            startActivity(intent)
        }
    }

    /** Updates the HOST / LISTENER toggle visual state. */
    private fun updateModeToggle() {
        if (isHostMode) {
            binding.btnModeHost.setBackgroundResource(R.drawable.bg_btn_primary)
            binding.btnModeHost.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnModeListener.setBackgroundResource(android.R.color.transparent)
            binding.btnModeListener.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.hostInfoLayout.visibility = View.VISIBLE
            binding.listenerInputLayout.visibility = View.GONE
        } else {
            binding.btnModeListener.setBackgroundResource(R.drawable.bg_btn_primary)
            binding.btnModeListener.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.btnModeHost.setBackgroundResource(android.R.color.transparent)
            binding.btnModeHost.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.hostInfoLayout.visibility = View.GONE
            binding.listenerInputLayout.visibility = View.VISIBLE
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Button State Helpers
    // ═════════════════════════════════════════════════════════════════════

    private fun setHostButtonActive() {
        binding.btnStartHost.setBackgroundResource(R.drawable.bg_btn_stop)
        binding.btnStartHost.text = "STOP STREAMING"
        binding.tvClientCount.visibility = View.VISIBLE
        startPulseAnimation()
    }

    private fun setHostButtonIdle() {
        binding.btnStartHost.setBackgroundResource(R.drawable.bg_btn_primary)
        binding.btnStartHost.text = "START STREAMING"
        binding.tvClientCount.visibility = View.GONE
        binding.tvClientCount.text = "0 clients connected"
        stopPulseAnimation()
    }

    private fun setListenerButtonActive() {
        binding.btnConnect.setBackgroundResource(R.drawable.bg_btn_stop)
        binding.btnConnect.text = "DISCONNECT"
        startPulseAnimation()
    }

    private fun setListenerButtonIdle() {
        binding.btnConnect.setBackgroundResource(R.drawable.bg_btn_primary)
        binding.btnConnect.text = "CONNECT"
        stopPulseAnimation()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Host Mode
    // ═════════════════════════════════════════════════════════════════════

    private fun startHost() {
        if (!hasPermissions()) {
            checkPermissions(); return
        }

        if (binding.switchInternalAudio.isChecked) {
            startForegroundServiceCompat()
            val mgr = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        } else {
            isStreaming = true
            startHostWithProjection(null)
        }

        // Debounce
        binding.btnStartHost.isEnabled = false
        binding.btnStartHost.postDelayed({ binding.btnStartHost.isEnabled = true }, 1000)
    }

    private fun startHostWithProjection(projection: MediaProjection?) {
        initAudioStreamManager()
        acquireLocks()
        audioStreamManager?.startHost(projection)
        setHostButtonActive()
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Listener Mode
    // ═════════════════════════════════════════════════════════════════════

    private fun startListener(ip: String) {
        if (!hasPermissions()) { checkPermissions(); return }
        initAudioStreamManager()
        acquireLocks()
        startForegroundServiceCompat()
        audioStreamManager?.startListener(ip)
        isStreaming = true
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Stop
    // ═════════════════════════════════════════════════════════════════════

    private fun stopStreaming() {
        audioStreamManager?.stop()
        audioStreamManager = null
        stopService(Intent(this, AudioCaptureService::class.java))
        releaseLocks()
        isStreaming = false
    }

    // ═════════════════════════════════════════════════════════════════════
    //  AudioStreamManager
    // ═════════════════════════════════════════════════════════════════════

    private fun initAudioStreamManager() {
        if (audioStreamManager == null) {
            audioStreamManager = AudioStreamManager(
                context = this,
                onStatus = { msg -> runOnUiThread { appendStatus(msg) } },
                onClientCountChanged = { count ->
                    runOnUiThread {
                        binding.tvClientCount.text = "$count client(s) connected"
                    }
                }
            )
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  LIVE Pulse Animation
    // ═════════════════════════════════════════════════════════════════════

    private fun startPulseAnimation() {
        binding.liveIndicator.visibility = View.VISIBLE
        val ring = binding.viewPulseRing

        val sx = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 2.8f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
        }
        val sy = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 2.8f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
        }
        val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.8f, 0f).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode  = ValueAnimator.RESTART
        }

        pulseAnimatorSet = AnimatorSet().apply {
            playTogether(sx, sy, alpha)
            duration     = 1200L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimatorSet?.cancel()
        pulseAnimatorSet = null
        binding.liveIndicator.visibility = View.GONE
        binding.viewPulseRing.apply {
            scaleX = 1f; scaleY = 1f; alpha = 0.8f
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Status Log
    // ═════════════════════════════════════════════════════════════════════

    private fun appendStatus(message: String) {
        val lines = binding.tvStatus.text.split("\n").toMutableList()
        lines.add(message)
        if (lines.size > MAX_LOG_LINES) lines.subList(0, lines.size - MAX_LOG_LINES).clear()
        binding.tvStatus.text = lines.joinToString("\n")
        (binding.tvStatus.parent as? android.widget.ScrollView)
            ?.post { (binding.tvStatus.parent as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  IP Address + QR Code
    // ═════════════════════════════════════════════════════════════════════

    private fun displayLocalIp() {
        val ips = mutableListOf<String>()
        try {
            // getNetworkInterfaces() returns null on some Android devices
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            ips.add(ip)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        if (ips.isEmpty()) {
            binding.tvLocalIp.text = "No IP found"
            binding.btnCopyIp.isEnabled = false
        } else {
            bestIp = ips.firstOrNull { it.startsWith("192.168.") } ?: ips.first()
            val fullAddress = "$bestIp:8080"
            binding.tvLocalIp.text = if (ips.size == 1) fullAddress else ips.joinToString("\n") { "$it:8080" }
            binding.btnCopyIp.isEnabled = true

            val url = "http://$bestIp:8080"
            Thread {
                val bmp = generateQrBitmap(url)
                runOnUiThread {
                    if (bmp != null) {
                        binding.ivQrCode.setImageBitmap(bmp)
                        binding.cardQrCode.visibility = View.VISIBLE
                    }
                }
            }.start()
        }
    }

    private fun generateQrBitmap(text: String, size: Int = 400): Bitmap? {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val pixels = IntArray(size * size) { i ->
                if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) { null }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  WiFi Lock + Wake Lock
    // ═════════════════════════════════════════════════════════════════════

    @SuppressLint("WifiManagerLeak")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WifiAudioStreamer:WifiLock")
        }
        if (wifiLock?.isHeld == false) wifiLock?.acquire()

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiAudioStreamer:WakeLock")
        }
        if (wakeLock?.isHeld == false) wakeLock?.acquire()
    }

    private fun releaseLocks() {
        if (wifiLock?.isHeld == true) wifiLock?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wifiLock = null; wakeLock = null
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Permissions + helpers
    // ═════════════════════════════════════════════════════════════════════

    private fun startForegroundServiceCompat() {
        val intent = Intent(this, AudioCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissions() {
        val missing = PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
    }
}
