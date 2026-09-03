package com.wabridge.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * FIX (02.9.2026): companion to WaNotificationListener's normal media
 * flow, for the case a file is over MEDIA_HARD_DROP_CAP_BYTES (30MB) but
 * within CHUNK_UPLOAD_ABSOLUTE_MAX_BYTES (150MB). Rather than embedding
 * the whole file as base64 inside a single JSON doPost body - which for
 * a file this size would push the request close to (or past) Apps
 * Script's doPost payload ceiling, since base64 already inflates raw
 * bytes by ~37% - this uploads it to Code.gs's new chunked-upload
 * endpoint as a series of small, safe requests:
 *
 *   1. one "startChunkUpload" call (classifies the message and opens a
 *      session, mirroring what a normal doPost would do)
 *   2. N "uploadChunk" calls, each carrying one small raw chunk
 *   3. Code.gs reassembles all chunks into a single Drive file on the
 *      last chunk and sends a SEPARATE follow-up email with the link
 *
 * This runs entirely in the background on its own thread - it does not
 * block or delay the normal plain-text/other-media email, which has
 * already gone out by the time this is invoked (see the call site in
 * WaNotificationListener.attachMediaIfAny). A failure here is logged
 * and otherwise silent - the recipient already got a note in the main
 * email that this file was being uploaded separately, so there's
 * nothing else to fall back to if it doesn't complete.
 */
object ChunkedMediaUploader {
    private const val TAG = "WaBridge"

    // 6MB raw per chunk -> ~8.2MB once base64-encoded, comfortably
    // small for a single POST request regardless of what Apps Script's
    // exact doPost ceiling turns out to be. MUST stay reasonably small;
    // this is not tuned for speed, just for reliability of an
    // infrequent large-file case.
    private const val CHUNK_SIZE_BYTES = 6 * 1024 * 1024

    fun uploadInBackground(
        webAppUrl: String,
        file: File,
        mimeType: String,
        title: String,
        text: String,
        phone: String?,
        isGroup: Boolean?
    ) {
        Thread({
            try {
                upload(webAppUrl, file, mimeType, title, text, phone, isGroup)
            } catch (e: Exception) {
                Log.e(TAG, "Chunked upload failed (non-fatal)", e)
                EventLog.log("Listener: ❌ העלאה בחלקים נכשלה עבור ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }, "wabridge-chunk-upload").start()
    }

    private fun upload(
        webAppUrl: String,
        file: File,
        mimeType: String,
        title: String,
        text: String,
        phone: String?,
        isGroup: Boolean?
    ) {
        val sessionId = UUID.randomUUID().toString()
        val totalSize = file.length()
        val totalChunks = ((totalSize + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt().coerceAtLeast(1)

        val startBody = JSONObject().apply {
            put("action", "startChunkUpload")
            put("sessionId", sessionId)
            put("title", title)
            put("text", text)
            if (phone != null) put("phone", phone)
            if (isGroup != null) put("isGroup", isGroup)
            put("fileName", file.name)
            put("mimeType", mimeType)
            put("totalSize", totalSize)
        }
        val startResponse = postJson(webAppUrl, startBody.toString())
        if (!isOk(startResponse)) {
            Log.w(TAG, "startChunkUpload rejected: $startResponse")
            EventLog.log("Listener: ❌ שרת סירב להתחיל העלאה בחלקים עבור ${file.name}: $startResponse")
            return
        }

        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE_BYTES)
            var index = 0
            var bytesSentTotal = 0L

            while (true) {
                // Fill the buffer as full as possible - a single
                // InputStream.read() call is not guaranteed to return
                // CHUNK_SIZE_BYTES bytes even when that many remain.
                var filled = 0
                while (filled < buffer.size) {
                    val readNow = input.read(buffer, filled, buffer.size - filled)
                    if (readNow <= 0) break
                    filled += readNow
                }
                if (filled <= 0) break

                bytesSentTotal += filled
                val isLast = bytesSentTotal >= totalSize
                val chunkBase64 = android.util.Base64.encodeToString(buffer, 0, filled, android.util.Base64.NO_WRAP)

                val chunkBody = JSONObject().apply {
                    put("action", "uploadChunk")
                    put("sessionId", sessionId)
                    put("chunkIndex", index)
                    put("isLast", isLast)
                    put("dataBase64", chunkBase64)
                }
                val chunkResponse = postJson(webAppUrl, chunkBody.toString())
                if (!isOk(chunkResponse)) {
                    Log.w(TAG, "uploadChunk $index rejected: $chunkResponse")
                    EventLog.log("Listener: ❌ העלאת חלק ${index + 1}/$totalChunks נכשלה עבור ${file.name}: $chunkResponse")
                    return
                }

                EventLog.log("Listener: 📤 הועלה חלק ${index + 1}/$totalChunks (${file.name})")
                index++
                if (isLast) break
            }
        }

        EventLog.log("Listener: ✅ העלאה בחלקים הושלמה, מייל נפרד עם קישור ה-Drive יישלח: ${file.name}")
    }

    private fun postJson(webAppUrl: String, json: String): String {
        val url = URL(webAppUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = 15000
            // Each chunk request also triggers a Drive write (and, on
            // the last chunk, a full reassembly + upload + email) on
            // the Apps Script side - give it more headroom than a
            // plain-text notification ever needed.
            // FIX (03.9.2026): the LAST chunk triggers Code.gs to read
            // back every chunk file, reassemble them, upload the result
            // to Drive, and send an email - all inside that one request.
            // 60s cut this close for a large file with many chunks;
            // 120s gives real headroom while staying well under Apps
            // Script's own ~6 minute execution ceiling.
            readTimeout = 120000
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(json) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun isOk(responseBody: String): Boolean {
        return try {
            JSONObject(responseBody).optBoolean("ok", false)
        } catch (e: Exception) {
            false
        }
    }
}
