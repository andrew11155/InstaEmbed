package com.instaembed.instaembed

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ShareActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private val igAppId = "936619743392459"

    private val filesToCleanup = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (text == null) {
            finish(); return
        }

        val shortcode = extractShortcode(text)
        if (shortcode == null) {
            toast("No Instagram URL found"); finish(); return
        }

        toast("Fetching video...")

        Thread {
            try {
                processShare(shortcode)
            } catch (e: Exception) {
                handler.post {
                    toast("Error: ${e.message}")
                    cleanupFiles(); finish()
                }
                return@Thread
            }
            handler.post { cleanupFiles(); finish() }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupFiles()
    }

    private fun cleanupFiles() {
        filesToCleanup.forEach { f ->
            try { if (f.exists()) f.delete() } catch (_: Exception) {}
        }
        filesToCleanup.clear()
    }

    private fun extractShortcode(url: String): String? {
        val patterns = listOf(
            Regex("""instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_-]+)"""),
            Regex("""instagr\.am/(?:reel|p|tv)/([A-Za-z0-9_-]+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun processShare(shortcode: String) {
        // Get cookies
        val initConn = URL("https://www.instagram.com/").openConnection() as HttpURLConnection
        initConn.setRequestProperty("User-Agent", ua)
        initConn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        initConn.connectTimeout = 10000
        initConn.readTimeout = 10000
        initConn.instanceFollowRedirects = true
        initConn.connect()

        val allCookies = mutableListOf<String>()
        initConn.headerFields.forEach { (key, values) ->
            if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                values?.forEach { allCookies.add(it.split(";").first()) }
            }
        }
        initConn.disconnect()

        val csrfToken = allCookies
            .firstOrNull { it.startsWith("csrftoken=") }
            ?.substringAfter("=") ?: throw Exception("No CSRF token")

        val cookieHeader = allCookies.joinToString("; ")

        handler.post { toast("Fetching post...") }

        val variables = JSONObject().apply {
            put("shortcode", shortcode)
            put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
        }.toString()

        val conn = URL("https://www.instagram.com/graphql/query/").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("X-IG-App-ID", igAppId)
        conn.setRequestProperty("X-CSRFToken", csrfToken)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Cookie", cookieHeader)
        conn.setRequestProperty("Referer", "https://www.instagram.com/")
        conn.setRequestProperty("Origin", "https://www.instagram.com")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000

        val body = "doc_id=27128499623469141&variables=${java.net.URLEncoder.encode(variables, "UTF-8")}"
        conn.outputStream.buffered().use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        val responseText = if (code == 200) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "no body"
            conn.disconnect()
            throw Exception("API $code: ${err.take(200)}")
        }
        conn.disconnect()

        val json = JSONObject(responseText)
        val items = json
            .optJSONObject("data")
            ?.optJSONObject("xdt_api__v1__media__shortcode__web_info")
            ?.optJSONArray("items")
        if (items == null || items.length() == 0) throw Exception("No items in response")

        val item = items.getJSONObject(0)
        val videos = item.optJSONArray("video_versions")
        var videoUrl: String? = null
        if (videos != null && videos.length() > 0) {
            videoUrl = videos.getJSONObject(0).optString("url")
        }
        if (videoUrl == null) throw Exception("No video URL")

        handler.post { toast("Downloading...") }

        val originalFile = File(cacheDir, "instaembed_$shortcode.mp4")
        filesToCleanup.add(originalFile)
        downloadFile(videoUrl, originalFile)

        val size = originalFile.length()
        val readable = VideoCompressor.getReadableSize(size)

        var fileToShare = originalFile

        if (VideoCompressor.needsCompression(originalFile)) {
            val compressedFile = File(cacheDir, "instaembed_${shortcode}_compressed.mp4")
            filesToCleanup.add(compressedFile)
            handler.post { toast("Compressing $readable...") }

            try {
                VideoCompressor.compress(originalFile, compressedFile) { pct ->
                    if (pct == 100) {
                        handler.post { toast("Finishing encoding...") }
                    } else if (pct % 25 == 0) {
                        handler.post { toast("Compressing... $pct%") }
                    }
                }
                if (compressedFile.exists() && compressedFile.length() > 0) {
                    val compressedReadable = VideoCompressor.getReadableSize(compressedFile.length())
                    handler.post { toast("Compressed: $readable → $compressedReadable") }
                    fileToShare = compressedFile
                } else {
                    handler.post { toast("Compression failed, sharing original") }
                }
            } catch (e: Exception) {
                handler.post { toast("Compression failed, sharing original") }
            }
        }

        handler.post { shareFile(fileToShare) }
    }

    private fun downloadFile(urlStr: String, dest: File) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", ua)
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.connect()
        dest.outputStream().use { out -> conn.inputStream.use { it.copyTo(out) } }
        conn.disconnect()
    }

    private fun shareFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
