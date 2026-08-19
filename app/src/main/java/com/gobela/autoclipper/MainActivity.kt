package com.gobela.autoclipper

import android.content.ContentResolver
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var keyInput: EditText
    private lateinit var keyStatus: TextView
    private lateinit var checkResult: TextView
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var videoUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("autoclipper", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val PICK_VIDEO = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        keyInput = findViewById(R.id.keyInput)
        keyStatus = findViewById(R.id.keyStatus)
        checkResult = findViewById(R.id.checkResult)
        result = findViewById(R.id.result)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        refreshKeys()

        findViewById<Button>(R.id.addKey).setOnClickListener { addKey() }
        findViewById<Button>(R.id.deleteKey).setOnClickListener {
            prefs.edit().remove("keys").apply(); refreshKeys(); toast("Semua API key dihapus")
        }
        findViewById<Button>(R.id.checkKey).setOnClickListener { checkKeys() }
        findViewById<Button>(R.id.pickVideo).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            }, PICK_VIDEO)
        }
        findViewById<Button>(R.id.analyze).setOnClickListener { analyzeVideo() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_VIDEO && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                videoUri = uri
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
                findViewById<TextView>(R.id.videoPath).text = getFileName(uri)
                status.text = "Video siap dianalisis."
            }
        }
    }

    private fun getKeys(): MutableList<String> {
        val arr = JSONArray(prefs.getString("keys", "[]") ?: "[]")
        return MutableList(arr.length()) { i -> arr.getString(i) }
    }

    private fun saveKeys(keys: List<String>) {
        val arr = JSONArray(); keys.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach(arr::put)
        prefs.edit().putString("keys", arr.toString()).apply()
    }

    private fun refreshKeys() {
        val keys = getKeys()
        keyStatus.text = if (keys.isEmpty()) "Belum ada API key tersimpan." else "${keys.size} key tersimpan. Contoh: ${mask(keys.first())}"
    }

    private fun mask(k: String) = if (k.length < 10) "••••" else k.take(4) + "••••••" + k.takeLast(4)

    private fun addKey() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) { toast("Masukkan Gemini API Key"); return }
        val keys = getKeys(); if (!keys.contains(key)) keys.add(key); saveKeys(keys)
        keyInput.text.clear(); refreshKeys(); toast("Key ditambahkan")
    }

    private fun checkKeys() {
        val keys = getKeys()
        if (keys.isEmpty()) { toast("Masukkan key lalu tekan Tambah Key"); return }
        setBusy(true); checkResult.text = "Mengecek ${keys.size} key..."
        executor.execute {
            var valid = 0; var limited = 0; var invalid = 0; var errors = 0
            keys.forEach { key ->
                val r = geminiTextCall(key, "Reply only with OK")
                when {
                    r.startsWith("OK:") -> valid++
                    r.contains("429") -> limited++
                    r.contains("400") || r.contains("401") || r.contains("403") -> invalid++
                    else -> errors++
                }
            }
            runOnUiThread { checkResult.text = "Hasil: $valid VALID, $limited rate-limited, $invalid INVALID, $errors ERROR."; setBusy(false) }
        }
    }

    private fun analyzeVideo() {
        val keys = getKeys(); val uri = videoUri
        val duration = findViewById<EditText>(R.id.clipDuration).text.toString().toIntOrNull()?.coerceIn(5, 600) ?: 45
        val count = findViewById<EditText>(R.id.clipCount).text.toString().toIntOrNull()?.coerceIn(1, 20) ?: 5
        if (keys.isEmpty()) { toast("Masukkan dan simpan Gemini API Key terlebih dahulu"); return }
        if (uri == null) { toast("Pilih video MP4 terlebih dahulu"); return }
        setBusy(true); result.text = ""; status.text = "Menyiapkan video untuk Gemini..."
        executor.execute {
            var last = ""
            for (key in keys) {
                try {
                    runOnUiThread { status.text = "Mengunggah video ke Gemini..." }
                    val file = uploadGeminiFile(key, uri)
                    runOnUiThread { status.text = "Gemini menganalisis video..." }
                    val prompt = """
                        Kamu adalah editor short-video profesional. Analisis video yang diunggah.
                        Pilih $count momen paling menarik, informatif, lucu, mengejutkan, atau dramatis.
                        Target durasi setiap clip sekitar $duration detik.
                        Video bisa berdurasi 1 jam atau lebih. Gunakan timestamp berdasarkan video asli.
                        Jangan mengarang timestamp. Setiap start harus lebih kecil dari end.
                        Balas HANYA JSON valid dengan struktur:
                        {"clips":[{"title":"...","start":12.5,"end":57.0,"score":92,"reason":"..."}]}
                        Urutkan dari skor tertinggi. Jangan memakai markdown.
                    """.trimIndent()
                    val response = geminiVideoCall(key, file.first, file.second, prompt)
                    if (response.startsWith("OK:")) {
                        last = response.removePrefix("OK:")
                        val clips = parseClips(last)
                        if (clips.isNotEmpty()) {
                            runOnUiThread { result.text = formatClips(clips); status.text = "Momen ditemukan. Membuat ${clips.size} clip MP4..." }
                            val outputs = mutableListOf<File>()
                            clips.forEachIndexed { index, c ->
                                try {
                                    val out = createClip(uri, c.start, c.end, index + 1, c.title)
                                    outputs.add(out)
                                } catch (e: Exception) { }
                            }
                            runOnUiThread {
                                result.append("\n\nClip berhasil dibuat: ${outputs.size}/${clips.size}\nFolder: ${getExternalFilesDir(null)?.absolutePath}")
                                status.text = "Selesai."
                            }
                        }
                        break
                    } else last = response
                } catch (e: Exception) { last = "ERROR: ${e.message}" }
            }
            runOnUiThread { if (!last.startsWith("OK:") && last.isNotBlank()) { result.text = last; status.text = "Gagal." }; setBusy(false) }
        }
    }

    private data class Clip(val title: String, val start: Double, val end: Double, val score: Int, val reason: String)

    private fun parseClips(text: String): List<Clip> {
        val cleaned = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            val arr = JSONObject(cleaned).optJSONArray("clips") ?: JSONArray()
            val out = mutableListOf<Clip>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); val s = o.optDouble("start", -1.0); val e = o.optDouble("end", -1.0)
                if (s >= 0 && e > s) out.add(Clip(o.optString("title", "Clip ${i+1}"), s, e, o.optInt("score", 0), o.optString("reason", "")))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun formatClips(clips: List<Clip>): String = clips.mapIndexed { i, c ->
        "#${i+1} ${c.title}\n${time(c.start)} → ${time(c.end)} • ${c.score}/100\n${c.reason}"
    }.joinToString("\n\n")

    private fun time(sec: Double): String { val s = sec.toInt(); return String.format("%02d:%02d", s / 60, s % 60) }

    private fun createClip(uri: Uri, startSec: Double, endSec: Double, index: Int, title: String): File {
        val extractor = MediaExtractor(); extractor.setDataSource(this, uri, null)
        val outDir = File(getExternalFilesDir(null), "clips"); outDir.mkdirs()
        val safe = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(50)
        val out = File(outDir, String.format("clip_%02d_%s.mp4", index, if (safe.isBlank()) "video" else safe))
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = HashMap<Int, Int>()
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) map[i] = muxer.addTrack(format)
        }
        muxer.start()
        val startUs = (startSec * 1_000_000).toLong(); val endUs = (endSec * 1_000_000).toLong()
        val buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024); val info = android.media.MediaCodec.BufferInfo()
        for ((track, outTrack) in map) {
            extractor.unselectTrack(track); extractor.selectTrack(track); extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            while (true) {
                val sampleTime = extractor.sampleTime; if (sampleTime < 0 || sampleTime > endUs) break
                buffer.clear(); val size = extractor.readSampleData(buffer, 0); if (size < 0) break
                info.offset = 0; info.size = size; info.presentationTimeUs = sampleTime; info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info); extractor.advance()
            }
            extractor.unselectTrack(track)
        }
        muxer.stop(); muxer.release(); extractor.release(); return out
    }

    private fun uploadGeminiFile(key: String, uri: Uri): Pair<String, String> {
        val mime = contentResolver.getType(uri) ?: "video/mp4"
        val size = contentLength(uri)
        val start = URL("https://generativelanguage.googleapis.com/upload/v1beta/files?key=${URLEncoder.encode(key, "UTF-8")}").openConnection() as HttpURLConnection
        start.requestMethod = "POST"; start.doOutput = true; start.connectTimeout = 30000; start.readTimeout = 30000
        start.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        start.setRequestProperty("X-Goog-Upload-Command", "start")
        start.setRequestProperty("X-Goog-Upload-Header-Content-Length", size.toString())
        start.setRequestProperty("X-Goog-Upload-Header-Content-Type", mime)
        start.setRequestProperty("Content-Type", "application/json")
        start.outputStream.use { it.write(JSONObject().put("file", JSONObject().put("display_name", getFileName(uri))).toString().toByteArray()) }
        val code = start.responseCode; if (code !in 200..299) throw IOException("Gemini upload start HTTP $code: ${readError(start)}")
        val uploadUrl = start.headerFields.entries.firstOrNull { it.key?.equals("X-Goog-Upload-URL", true) == true }?.value?.firstOrNull()
            ?: throw IOException("Gemini upload URL tidak ditemukan")
        start.disconnect()
        val upload = URL(uploadUrl).openConnection() as HttpURLConnection
        upload.requestMethod = "POST"; upload.doOutput = true; upload.connectTimeout = 30000; upload.readTimeout = 10 * 60 * 1000
        upload.setRequestProperty("Content-Length", size.toString())
        upload.setRequestProperty("X-Goog-Upload-Offset", "0")
        upload.setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
        upload.setRequestProperty("Content-Type", mime)
        contentResolver.openInputStream(uri)!!.use { input -> upload.outputStream.use { out -> val buf = ByteArray(1024 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) } } }
        val ucode = upload.responseCode; val body = if (ucode in 200..299) upload.inputStream.bufferedReader().use { it.readText() } else readError(upload)
        if (ucode !in 200..299) throw IOException("Gemini upload HTTP $ucode: $body")
        val file = JSONObject(body).optJSONObject("file") ?: JSONObject(body)
        val uriOut = file.optString("uri"); val name = file.optString("name")
        if (uriOut.isBlank()) throw IOException("Gemini tidak mengembalikan file URI")
        return uriOut to (file.optString("mimeType").ifBlank { mime })
    }

    private fun geminiVideoCall(key: String, fileUri: String, mime: String, prompt: String): String {
        val model = "gemini-2.5-flash"
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${URLEncoder.encode(key, "UTF-8")}")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 30000; conn.readTimeout = 10 * 60 * 1000
        conn.setRequestProperty("Content-Type", "application/json")
        val part = JSONObject().put("file_data", JSONObject().put("mime_type", mime).put("file_uri", fileUri))
        val text = JSONObject().put("text", prompt)
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part).put(text)))).toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode; val raw = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else readError(conn)
        if (code !in 200..299) return "HTTP $code: $raw"
        val root = JSONObject(raw); val parts = root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        return "OK:" + (parts?.optJSONObject(0)?.optString("text", "") ?: "")
    }

    private fun geminiTextCall(key: String, prompt: String): String {
        return try {
            val model = "gemini-2.5-flash"
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${URLEncoder.encode(key, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 20000; conn.readTimeout = 60000; conn.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode; val raw = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else readError(conn)
            if (code !in 200..299) "HTTP $code: $raw" else "OK:" + JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text", "")
        } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    private fun contentLength(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) return c.getLong(i) } }
        throw IOException("Ukuran video tidak diketahui")
    }

    private fun getFileName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return "video.mp4"
    }

    private fun readError(conn: HttpURLConnection): String = try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
    private fun setBusy(busy: Boolean) { progress.visibility = if (busy) View.VISIBLE else View.GONE; findViewById<Button>(R.id.analyze).isEnabled = !busy; findViewById<Button>(R.id.checkKey).isEnabled = !busy; findViewById<Button>(R.id.addKey).isEnabled = !busy }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
