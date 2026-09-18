package com.instaembed.instaembed

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
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

    companion object {
        const val DISCORD_MAX_BYTES = 20L * 1024 * 1024 // 20 MB max Discord free limit
        const val COMPRESSION_THRESHOLD_BYTES = 19L * 1024 * 1024 // Compress if > 19 MB
        const val TARGET_COMPRESSED_BYTES = 18L * 1024 * 1024 // Aim for ~18 MB
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }

        val action = intent.action
        val type = intent.type

        // 1. Check for multiple video URIs (ACTION_SEND_MULTIPLE)
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (!uris.isNullOrEmpty()) {
                startNotificationAndUpdates()
                Thread { processMultipleVideos(uris) }.start()
                return
            }
        }

        // 2. Check for single video URI (ACTION_SEND with EXTRA_STREAM)
        val isVideoType = type != null && (type.startsWith("video/") || type == "application/mp4" || type == "application/octet-stream")
        val streamUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: if (isVideoType) {
            intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: intent.data
        } else null

        if (streamUri != null && (isVideoType || action == Intent.ACTION_SEND)) {
            val mimeType = type ?: contentResolver.getType(streamUri)
            val isLikelyVideo = mimeType == null || mimeType.startsWith("video/") || mimeType == "application/mp4" || mimeType == "application/octet-stream"
            if (isLikelyVideo) {
                startNotificationAndUpdates()
                Thread { processVideoShare(streamUri) }.start()
                return
            }
        }

        // 3. Check for Instagram text link
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null) {
            val shortcode = extractShortcode(text)
            if (shortcode != null) {
                startNotificationAndUpdates()
                Thread { processShare(text, shortcode) }.start()
                return
            }
        }

        finish()
    }

    private fun startNotificationAndUpdates() {
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED) {
            createNotificationChannel()
            showNotification("Preparing video...")
        }
        UpdateManager.checkForUpdateAsync(this)
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
            if (isVideo && tempFile.length() > COMPRESSION_THRESHOLD_BYTES) {
                updateNotification("Compressing video for Discord (<20MB)...")
                val compressed = compressVideo(tempFile, shortcode)
                if (compressed != null && compressed.exists() && compressed.length() > 0) {
                    fileToShare = compressed
                    Log.i(TAG, "Compressed video to ${compressed.length()} bytes")
                } else {
                    Log.w(TAG, "Compression failed or did not reduce size")
                    if (tempFile.length() > DISCORD_MAX_BYTES) {
                        Log.e(TAG, "Video is ${tempFile.length()} bytes (> 20MB) and compression failed; aborting share")
                        showToast("Failed to compress video to under Discord's 20MB limit.")
                        tempFile.delete()
                        cleanupAndFinish()
                        return
                    }
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
                clipData = ClipData.newRawUri(null, uri)
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

    private fun processVideoShare(uri: Uri) {
        try {
            updateNotification("Preparing video...")

            val timestamp = System.currentTimeMillis()
            val originalName = getFileName(uri) ?: "video.mp4"
            val extension = originalName.substringAfterLast('.', "mp4")
            val tempFile = File(cacheDir, "instaembed_shared_${timestamp}.${extension}")

            val copied = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile.exists() && tempFile.length() > 0
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy video stream: ${e.message}", e)
                false
            }

            if (!copied || tempFile.length() == 0L) {
                Log.e(TAG, "Failed to read video stream from $uri")
                tempFile.delete()
                cleanupAndFinish()
                return
            }

            Log.i(TAG, "Received video: ${tempFile.length()} bytes to ${tempFile.path}")

            var fileToShare = tempFile
            if (tempFile.length() > COMPRESSION_THRESHOLD_BYTES) {
                updateNotification("Compressing video for Discord (<20MB)...")
                val compressed = compressVideo(tempFile, "shared_${timestamp}")
                if (compressed != null && compressed.exists() && compressed.length() > 0) {
                    fileToShare = compressed
                    Log.i(TAG, "Compressed video to ${compressed.length()} bytes")
                } else {
                    Log.w(TAG, "Compression failed or did not reduce size")
                    if (tempFile.length() > DISCORD_MAX_BYTES) {
                        Log.e(TAG, "Video is ${tempFile.length()} bytes (> 20MB) and compression failed; aborting share")
                        showToast("Failed to compress video to under Discord's 20MB limit.")
                        tempFile.delete()
                        cleanupAndFinish()
                        return
                    }
                }
            }

            val shareUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                fileToShare
            )
            Log.i(TAG, "Sharing URI: $shareUri, size: ${fileToShare.length()}")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = ClipData.newRawUri(null, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            dismissNotification()

            pendingCleanupFiles.add(tempFile)
            if (fileToShare != tempFile) pendingCleanupFiles.add(fileToShare)

            shareLaunched = true
            startActivity(Intent.createChooser(shareIntent, null))

        } catch (e: Exception) {
            Log.e(TAG, "processVideoShare failed: ${e.message}", e)
            cleanupAndFinish()
        }
    }

    private fun processMultipleVideos(uris: List<Uri>) {
        try {
            val timestamp = System.currentTimeMillis()
            val filesToShare = mutableListOf<File>()
            val total = uris.size

            for ((index, uri) in uris.withIndex()) {
                val fileNum = index + 1
                updateNotification("Preparing video $fileNum of $total...")
                val originalName = getFileName(uri) ?: "video_$index.mp4"
                val extension = originalName.substringAfterLast('.', "mp4")
                val tempFile = File(cacheDir, "instaembed_shared_${timestamp}_$index.${extension}")

                val copied = try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.exists() && tempFile.length() > 0
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy video stream $index: ${e.message}", e)
                    false
                }

                if (!copied || tempFile.length() == 0L) {
                    tempFile.delete()
                    continue
                }

                pendingCleanupFiles.add(tempFile)

                var targetFile = tempFile
                if (tempFile.length() > COMPRESSION_THRESHOLD_BYTES) {
                    updateNotification("Compressing video $fileNum of $total (<20MB)...")
                    val compressed = compressVideo(tempFile, "shared_${timestamp}_$index")
                    if (compressed != null && compressed.exists() && compressed.length() > 0) {
                        targetFile = compressed
                        pendingCleanupFiles.add(compressed)
                        Log.i(TAG, "Compressed video $fileNum to ${compressed.length()} bytes")
                    } else {
                        Log.w(TAG, "Compression failed for video $fileNum")
                        if (tempFile.length() > DISCORD_MAX_BYTES) {
                            Log.e(TAG, "Video $fileNum is ${tempFile.length()} bytes (> 20MB) and compression failed; skipping")
                            continue
                        }
                    }
                }
                filesToShare.add(targetFile)
            }

            if (filesToShare.isEmpty()) {
                Log.e(TAG, "No valid videos to share")
                showToast("Failed to compress video(s) to under Discord's 20MB limit.")
                cleanupAndFinish()
                return
            }

            val shareUris = ArrayList(filesToShare.map { file ->
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            })

            val shareIntent = if (shareUris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, shareUris[0])
                    clipData = ClipData.newRawUri(null, shareUris[0])
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "video/mp4"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, shareUris)
                    clipData = ClipData.newRawUri(null, shareUris[0]).apply {
                        for (i in 1 until shareUris.size) {
                            addItem(ClipData.Item(shareUris[i]))
                        }
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

            dismissNotification()
            shareLaunched = true
            startActivity(Intent.createChooser(shareIntent, null))

        } catch (e: Exception) {
            Log.e(TAG, "processMultipleVideos failed: ${e.message}", e)
            cleanupAndFinish()
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun getFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) return cursor.getString(idx)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query filename: ${e.message}")
            }
        }
        return uri.lastPathSegment
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
            val success = VideoCompressor.compress(input, output, TARGET_COMPRESSED_BYTES)
            if (success && output.exists() && output.length() > 0) {
                return output
            }
            output.delete()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "compressVideo failed: ${e.message}", e)
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
