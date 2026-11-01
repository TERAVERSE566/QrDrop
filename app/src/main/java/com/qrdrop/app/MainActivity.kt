package com.qrdrop.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private var picked: Uri? = null
    private lateinit var fileLabel: TextView
    private lateinit var oneTime: Switch
    private lateinit var shareBtn: Button
    private lateinit var refreshBtn: Button
    private lateinit var qrView: ImageView
    private lateinit var urlText: TextView
    private lateinit var statusText: TextView
    private lateinit var receiptText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var lastQrUrl: String? = null
    private val ticker = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 500) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            picked = uri
            fileLabel.text = describe(uri)
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startSharing() // start even if denied; sharing still works, notification may be hidden
    }

    private val scanner = registerForActivityResult(ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult
        if (text.startsWith("http://")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(text)))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "Not a QR Drop code", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "QR Drop"; textSize = 28f; setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Send big files straight from this phone. No upload, no internet."
            setPadding(0, 0, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "1. Choose file"
            setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        })
        fileLabel = TextView(this).apply { text = "No file chosen"; setPadding(0, 8, 0, 8) }
        root.addView(fileLabel)

        oneTime = Switch(this).apply { text = "One-time link (dies after first full download)" }
        root.addView(oneTime)

        shareBtn = Button(this).apply {
            text = "2. Start sharing"
            setOnClickListener { onShareClicked() }
        }
        root.addView(shareBtn)

        qrView = ImageView(this).apply { adjustViewBounds = true }
        root.addView(qrView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        urlText = TextView(this).apply { gravity = Gravity.CENTER; setTextIsSelectable(true) }
        root.addView(urlText)
        statusText = TextView(this).apply { setPadding(0, 12, 0, 12) }
        root.addView(statusText)
        receiptText = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(receiptText)

        refreshBtn = Button(this).apply {
            text = "Refresh address (after turning on hotspot / Wi-Fi)"
            setOnClickListener {
                startService(Intent(this@MainActivity, ShareService::class.java)
                    .setAction(ShareService.ACTION_REFRESH))
            }
        }
        root.addView(refreshBtn)

        root.addView(Button(this).apply {
            text = "Open hotspot settings"
            setOnClickListener {
                try { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            }
        })

        root.addView(Button(this).apply {
            text = "Scan a QR to receive"
            setOnClickListener {
                scanner.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false)
                    .setPrompt("Scan the sender's QR Drop code"))
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }

    private fun onShareClicked() {
        if (ShareState.running) {
            startService(Intent(this, ShareService::class.java).setAction(ShareService.ACTION_STOP))
            return
        }
        if (picked == null) {
            Toast.makeText(this, "Choose a file first", Toast.LENGTH_SHORT).show(); return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startSharing()
    }

    private fun startSharing() {
        val uri = picked ?: return
        ContextCompat.startForegroundService(
            this,
            Intent(this, ShareService::class.java)
                .putExtra("uri", uri.toString())
                .putExtra("oneTime", oneTime.isChecked)
        )
    }

    private fun render() {
        val running = ShareState.running
        shareBtn.text = if (running) "Stop sharing" else "2. Start sharing"
        refreshBtn.isEnabled = running

        val url = ShareState.url
        if (running && url != null) {
            if (url != lastQrUrl) {
                qrView.setImageBitmap(BarcodeEncoder().encodeBitmap(url, BarcodeFormat.QR_CODE, 800, 800))
                lastQrUrl = url
            }
            urlText.text = url
            val sent = ShareState.bytesSent
            val total = ShareState.fileSize
            val pct = if (total > 0) (sent * 100 / total) else 0
            statusText.text = "Sharing ${ShareState.fileName}\nSent ${formatSize(sent)} of ${formatSize(total)} ($pct%)\n" +
                "Receiver must be on the same Wi-Fi, or connected to your hotspot."
        } else {
            qrView.setImageDrawable(null); lastQrUrl = null; urlText.text = ""
            statusText.text = ShareState.message
        }

        val r = ShareState.receipt
        receiptText.text = if (r == null) "" else
            "DELIVERY RECEIPT\nFile: ${r.name}\nSize: ${r.size} bytes\nTo: ${r.client}\n" +
            "Time: ${DateFormat.getDateTimeInstance().format(Date(r.time))}\nSHA-256:\n${r.sha256}"
    }

    private fun describe(uri: Uri): String {
        var name = "file"; var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return "$name  (${formatSize(size)})"
    }
}
