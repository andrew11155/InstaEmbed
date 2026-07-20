package com.instaembed.instaembed

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ShareActivity : Activity() {

    private val TAG = "InstaEmbed"
    private val CHANNEL_ID = "instaembed_progress"
    private val NOTIFICATION_ID = 1001
    private val handler = Handler(Looper.getMainLooper())
    private var shareLaunched = false
    private var pendingCleanupFiles = mutableListOf<File>()

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private val IG_APP_ID = "936619743392459"
    private val GRAPHQL_ENDPOINT = "https://www.instagram.com/graphql/query/"
    private val DOC_ID = "27128499623469141"
    private val DISCORD_FREE_LIMIT = 10L * 1024 * 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: run {
            finish()
            return
        }

        val shortcode = extractShortcode(text)
        if (shortcode == null) {
            finish()
            return
        }

        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
            createNotificationChannel()
            showNotification("Preparing video...")
        }

        Thread { processShare(text, shortcode) }.start()
    }

    override fun onResume() {
        super.onResume()
        if (shareLaunched) {
            shareLaunched = false
            handler.postDelayed({
                for (f in pendingCleanupFiles) f.delete()
                pendingCleanupFiles.clear()
                finish()
            }, 10000)
        }
    }

    private fun processShare(url: String, shortcode: String) {
        try {
            updateNotification("Fetching post data...")

            val csrfToken = fetchCsrfToken()
            val postData = fetchGraphQL(shortcode, csrfToken)
            if (postData == null) {
                Log.e(TAG, "GraphQL returned null")
                cleanupAndFinish()
                return
            }

            val mediaUrl = postData.first
            val isVideo = postData.second

            updateNotification("Downloading video...")
            val tempFile = downloadFile(mediaUrl, shortcode)
            if (tempFile == null) {
                Log.e(TAG, "Download returned null")
                cleanupAndFinish()
                return
            }

            Log.i(TAG, "Downloaded ${tempFile.length()} bytes to ${tempFile.path}")

            var fileToShare = tempFile
            if (isVideo && tempFile.length() > DISCORD_FREE_LIMIT) {
                updateNotification("Compressing video...")
                val compressed = compressVideo(tempFile, shortcode)
                if (compressed != null && compressed.length() < tempFile.length()) {
                    fileToShare = compressed
                    Log.i(TAG, "Compressed to ${compressed.length()} bytes")
                }
            }

            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                fileToShare
            )
            Log.i(TAG, "Sharing URI: $uri, mimeType: $mimeType, size: ${fileToShare.length()}")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            dismissNotification()

            pendingCleanupFiles.add(tempFile)
            if (fileToShare != tempFile) pendingCleanupFiles.add(fileToShare)

            shareLaunched = true
            startActivity(Intent.createChooser(shareIntent, null))

        } catch (e: Exception) {
            Log.e(TAG, "processShare failed: ${e.message}", e)
            cleanupAndFinish()
        }
    }

    private fun cleanupAndFinish() {
        dismissNotification()
        handler.post { finish() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "InstaEmbed",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Video processing progress"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun showNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("InstaEmbed")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun updateNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("InstaEmbed")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun dismissNotification() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun fetchCsrfToken(): String? {
        try {
            val conn = URL("https://www.instagram.com/").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.connect()

            val cookieHeader = conn.headerFields
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }

            for (cookie in cookieHeader) {
                val match = Regex("csrftoken=([^;]+)").find(cookie)
                if (match != null) return match.groupValues[1]
            }
            return null
        } catch (e: Exception) {
            Log.e(TAG, "fetchCsrfToken failed: ${e.message}")
            return null
        }
    }

    private fun fetchGraphQL(shortcode: String, csrfToken: String?): Pair<String, Boolean>? {
        try {
            val variables = JSONObject().apply {
                put("shortcode", shortcode)
                put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            }

            val body = "doc_id=$DOC_ID&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}"

            val conn = URL(GRAPHQL_ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("X-IG-App-ID", IG_APP_ID)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            if (csrfToken != null) conn.setRequestProperty("X-CSRFToken", csrfToken)
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode != 200) {
                Log.e(TAG, "GraphQL returned ${conn.responseCode}")
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(response)
            val items = json
                .getJSONObject("data")
                .getJSONObject("xdt_api__v1__media__shortcode__web_info")
                .getJSONArray("items")

            if (items.length() == 0) return null

            val item = items.getJSONObject(0)

            val videoVersions = item.optJSONArray("video_versions")
            if (videoVersions != null && videoVersions.length() > 0) {
                val videoUrl = videoVersions.getJSONObject(0).getString("url")
                return Pair(videoUrl, true)
            }

            val imageVersions = item.optJSONObject("image_versions2")
            if (imageVersions != null) {
                val candidates = imageVersions.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val imageUrl = candidates.getJSONObject(0).getString("url")
                    return Pair(imageUrl, false)
                }
            }

            return null
        } catch (e: Exception) {
            Log.e(TAG, "fetchGraphQL failed: ${e.message}")
            return null
        }
    }

    private fun downloadFile(url: String, shortcode: String): File? {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download returned ${conn.responseCode}")
                return null
            }

            val file = File(cacheDir, "instaembed_$shortcode.mp4")
            conn.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            return file
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed: ${e.message}")
            return null
        }
    }

    private fun compressVideo(input: File, shortcode: String): File? {
        try {
            val output = File(cacheDir, "instaembed_${shortcode}_compressed.mp4")
            val success = VideoCompressor.compress(input, output)
            if (success && output.exists() && output.length() > 0) {
                return output
            }
            output.delete()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "compressVideo failed: ${e.message}")
            return null
        }
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
}
