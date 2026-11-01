package com.qrdrop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/** Shared state the UI polls. */
object ShareState {
    @Volatile var running = false
    @Volatile var url: String? = null
    @Volatile var fileName = ""
    @Volatile var fileSize = 0L
    @Volatile var bytesSent = 0L
    @Volatile var receipt: Receipt? = null
    @Volatile var message = ""
    fun reset() {
        running = false; url = null; fileName = ""; fileSize = 0; bytesSent = 0
        receipt = null; message = ""
    }
}

class ShareService : Service() {

    companion object {
        const val ACTION_STOP = "com.qrdrop.app.STOP"
        const val ACTION_REFRESH = "com.qrdrop.app.REFRESH"
        private const val CHANNEL = "qrdrop_share"
        private const val NOTIF_ID = 7
    }

    private var server: FileServer? = null
    private var token = ""
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_REFRESH -> { updateUrl(); return START_NOT_STICKY }
        }
        val uriStr = intent?.getStringExtra("uri")
        if (uriStr == null) { stopAll(); return START_NOT_STICKY }
        goForeground()
        begin(Uri.parse(uriStr), intent.getBooleanExtra("oneTime", false))
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "File sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("QR Drop is sharing a file")
            .setContentText("Keep this running until the transfer finishes")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun begin(uri: Uri, oneTime: Boolean) {
        server?.stop()
        ShareState.reset()

        var name = "file"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size <= 0) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            } catch (_: Exception) {}
        }
        if (size <= 0) {
            ShareState.message = "Could not read the file size."
            stopAll(keepMessage = true)
            return
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"

        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        var started: FileServer? = null
        for (port in 8080..8090) {
            val s = FileServer(
                port, contentResolver, uri, name, size, mime, token, oneTime,
                onProgress = { ShareState.bytesSent = it },
                onDelivered = { ShareState.receipt = it }
            )
            try {
                s.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                started = s
                break
            } catch (_: Exception) {}
        }
        if (started == null) {
            ShareState.message = "Could not start the server (ports busy)."
            stopAll(keepMessage = true)
            return
        }
        server = started

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "qrdrop").also { it.acquire() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qrdrop:share").also { it.acquire() }

        ShareState.fileName = name
        ShareState.fileSize = size
        ShareState.running = true
        updateUrl()
    }

    private fun updateUrl() {
        val s = server ?: return
        val ip = findIp()
        if (ip == null) {
            ShareState.url = null
            ShareState.message = "No Wi-Fi or hotspot found. Connect to the same Wi-Fi as the receiver, or turn on your hotspot, then tap Refresh."
        } else {
            ShareState.url = "http://$ip:${s.listeningPort}/d/$token"
            ShareState.message = ""
        }
    }

    /** Wi-Fi / hotspot / ethernet address only (never mobile data). */
    private fun findIp(): String? {
        val cands = mutableListOf<Pair<String, String>>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        cands.add(ni.name to a.hostAddress!!)
                    }
                }
            }
        } catch (_: Exception) {}
        val ok = listOf("wlan", "ap", "swlan", "eth", "p2p")
        return cands.firstOrNull { c -> ok.any { c.first.startsWith(it) } }?.second
    }

    private fun stopAll(keepMessage: Boolean = false) {
        server?.stop(); server = null
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        wifiLock = null; wakeLock = null
        val msg = ShareState.message
        ShareState.reset()
        if (keepMessage) ShareState.message = msg
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop()
        try { wifiLock?.release() } catch (_: Exception) {}
        try { wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
