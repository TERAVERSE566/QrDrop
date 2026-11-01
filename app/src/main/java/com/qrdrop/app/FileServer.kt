package com.qrdrop.app

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest

data class Receipt(
    val name: String,
    val size: Long,
    val sha256: String,
    val time: Long,
    val client: String
)

/**
 * Streams ONE file (never loads it into memory) to anyone who has the token.
 * Supports HTTP Range so interrupted 5-6 GB downloads can resume.
 */
class FileServer(
    port: Int,
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val name: String,
    private val size: Long,
    private val mime: String,
    private val token: String,
    private val oneTime: Boolean,
    private val onProgress: (Long) -> Unit,
    private val onDelivered: (Receipt) -> Unit
) : NanoHTTPD(port) {

    @Volatile private var disabled = false

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        if (disabled) return text(Response.Status.FORBIDDEN, "This link has already been used.")
        return when {
            path == "/d/$token" -> page()
            path == "/f/$token" || path.startsWith("/f/$token/") -> file(session)
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun text(status: Response.IStatus, msg: String) =
        newFixedLengthResponse(status, "text/plain", msg)

    private fun page(): Response {
        val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val link = "/f/$token/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>QR Drop</title>
            <style>body{font-family:sans-serif;max-width:480px;margin:12vh auto;padding:0 20px;text-align:center}
            a.b{display:inline-block;margin-top:24px;padding:16px 32px;background:#1565c0;color:#fff;
            border-radius:10px;text-decoration:none;font-size:18px}</style></head>
            <body><h2>$safe</h2><p>${formatSize(size)}</p>
            <a class="b" href="$link">Download</a>
            <p style="color:#888;margin-top:32px">Direct transfer from the sender's device.</p></body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun file(session: IHTTPSession): Response {
        val rangeHeader = session.headers["range"]
        var start = 0L
        var end = size - 1
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val p = rangeHeader.removePrefix("bytes=").split("-")
            try {
                if (p[0].isNotEmpty()) {
                    start = p[0].toLong()
                    if (p.size > 1 && p[1].isNotEmpty()) end = minOf(p[1].toLong(), size - 1)
                } else if (p.size > 1 && p[1].isNotEmpty()) {
                    start = maxOf(0L, size - p[1].toLong())
                }
            } catch (e: NumberFormatException) {
                start = 0L; end = size - 1
            }
        }
        if (start >= size || start > end) {
            val r = text(Response.Status.RANGE_NOT_SATISFIABLE, "Bad range")
            r.addHeader("Content-Range", "bytes */$size")
            return r
        }

        val pfd = resolver.openFileDescriptor(uri, "r")
            ?: return text(Response.Status.INTERNAL_ERROR, "Cannot open file")
        val fis = FileInputStream(pfd.fileDescriptor)
        fis.channel.position(start)

        val len = end - start + 1
        val full = start == 0L && end == size - 1
        val stream = TrackingStream(
            BufferedInputStream(fis, 1 shl 20), pfd, start, len, full,
            session.remoteIpAddress ?: "?"
        )
        val status = if (rangeHeader != null) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        val resp = newFixedLengthResponse(status, mime, stream, len)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader(
            "Content-Disposition",
            "attachment; filename=\"" + name.replace("\"", "") + "\"; filename*=UTF-8''" +
                URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        )
        if (status == Response.Status.PARTIAL_CONTENT) {
            resp.addHeader("Content-Range", "bytes $start-$end/$size")
        }
        return resp
    }

    private inner class TrackingStream(
        src: InputStream,
        private val pfd: ParcelFileDescriptor,
        private val startOffset: Long,
        private val expected: Long,
        private val full: Boolean,
        private val client: String
    ) : FilterInputStream(src) {
        private var count = 0L
        private var closed = false
        private val digest = if (full) MessageDigest.getInstance("SHA-256") else null

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) account(byteArrayOf(b.toByte()), 0, 1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) account(b, off, n)
            return n
        }

        private fun account(b: ByteArray, off: Int, n: Int) {
            digest?.update(b, off, n)
            count += n
            onProgress(startOffset + count)
        }

        override fun close() {
            if (closed) return
            closed = true
            try { super.close() } finally { try { pfd.close() } catch (_: Exception) {} }
            if (full && count == expected && digest != null) {
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                onDelivered(Receipt(name, size, hex, System.currentTimeMillis(), client))
                if (oneTime) disabled = true
            }
        }
    }
}

fun formatSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return "%.2f %s".format(v, units[i])
}
