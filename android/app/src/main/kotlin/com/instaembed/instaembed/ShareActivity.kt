package com.instaembed.instaembed

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ShareActivity : Activity() {

    private val TAG = "InstaEmbed"
    private val CHANNEL_ID = WorkService.CHANNEL_ID
    // Whether this activity holds a WorkService reference (keeps compression from being frozen)
    private var workActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var shareLaunched = false
    // Remaining parts of a split video, shared one at a time
    private val partQueue = ArrayDeque<File>()
    private var partTotal = 0
    private var continueParts = false

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    private val IG_APP_ID = "936619743392459"
    // Instagram 401s logged-out requests to /graphql/query/ since Oct 2026; /api/graphql still
    // works when it carries the page's LSD token
    private val GRAPHQL_ENDPOINT = "https://www.instagram.com/api/graphql"
    private val DOC_ID = "27128499623469141"

    companion object {
        const val DISCORD_MAX_BYTES = 20L * 1024 * 1024 // 20 MB max Discord free limit
        const val COMPRESSION_THRESHOLD_BYTES = 19L * 1024 * 1024 // Compress if > 19 MB
        const val TARGET_COMPRESSED_BYTES = 18L * 1024 * 1024 // Aim for ~18 MB
        const val ACTION_NEXT_PART = "com.instaembed.instaembed.NEXT_PART"
        private const val PART_NOTIFICATION_ID = 1002
        // Temp files older than this are leftovers from shares we never got to clean up
        private const val STALE_TEMP_FILE_MS = 2L * 60 * 60 * 1000
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

        // Tapped "share next part" notification; onResume picks it up
        if (action == ACTION_NEXT_PART) {
            continueParts = true
            return
        }

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

    /**
     * Shared files have to outlive the share itself (the receiving app may still be uploading
     * from them), so they are only removed once they are a couple of hours old.
     */
    private fun deleteStaleTempFiles() {
        val cutoff = System.currentTimeMillis() - STALE_TEMP_FILE_MS
        cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("instaembed_") && f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }

    private fun startNotificationAndUpdates() {
        Thread {
            deleteStaleTempFiles()
            SharedMedia.deleteStale(this)
        }.start()
        // Runs even without notification permission: the foreground service is what keeps
        // Samsung from freezing us mid-compression; the notification is just its visible side
        showNotification("Preparing video...")
        UpdateManager.checkForUpdateAsync(this)
    }

    override fun onResume() {
        super.onResume()
        if (shareLaunched || continueParts) {
            shareLaunched = false
            continueParts = false
            if (partQueue.isNotEmpty()) {
                handler.postDelayed({ shareNextPart() }, 600)
                return
            }
            dismissPartNotification()
            // Shared files are NOT deleted here: Discord keeps uploading from our content URI in
            // the background after the user is back, and deleting mid-upload left it stuck on
            // "sending" forever. deleteStaleTempFiles() removes them hours later instead.
            handler.postDelayed({ finish() }, 10000)
        }
    }

    private fun processShare(url: String, shortcode: String) {
        try {
            updateNotification("Fetching post data...")

            val session = fetchSession()
            val postData = fetchGraphQL(shortcode, session)
            if (postData == null) {
                Log.e(TAG, "GraphQL returned null")
                cleanupAndFinish()
                return
            }

            val mediaUrl = postData.first
            val isVideo = postData.second

            updateNotification("Downloading video...")
            val tempFile = downloadFile(mediaUrl, shortcode, if (isVideo) "mp4" else "jpg")
            if (tempFile == null) {
                Log.e(TAG, "Download returned null")
                cleanupAndFinish()
                return
            }

            Log.i(TAG, "Downloaded ${tempFile.length()} bytes to ${tempFile.path}")

            var fileToShare = tempFile
            if (isVideo) {
                val prepared = prepareVideo(tempFile, shortcode, allowSplit = true)
                if (prepared == null) {
                    tempFile.delete()
                    cleanupAndFinish()
                    return
                }
                if (prepared.size > 1) {
                    startPartShares(prepared)
                    return
                }
                fileToShare = prepared[0]
            }

            val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
            val size = fileToShare.length()
            val uri = SharedMedia.publish(this, fileToShare, mimeType)
            Log.i(TAG, "Sharing URI: $uri, mimeType: $mimeType, size: $size")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            dismissNotification()


            shareLaunched = true
            launchChooser(shareIntent, listOf(uri))

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

            val prepared = prepareVideo(tempFile, "shared_${timestamp}", allowSplit = true)
            if (prepared == null) {
                tempFile.delete()
                cleanupAndFinish()
                return
            }
            if (prepared.size > 1) {
                startPartShares(prepared)
                return
            }
            val fileToShare = prepared[0]

            val size = fileToShare.length()
            val shareUri = SharedMedia.publish(this, fileToShare, "video/mp4")
            Log.i(TAG, "Sharing URI: $shareUri, size: $size")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = ClipData.newRawUri(null, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            dismissNotification()

            shareLaunched = true
            launchChooser(shareIntent, listOf(shareUri))

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

                // Several videos already go out in one message, so don't split these further
                val prepared = prepareVideo(
                    tempFile, "shared_${timestamp}_$index", allowSplit = false,
                    label = "video $fileNum of $total", quiet = true
                ) ?: continue
                val targetFile = prepared[0]
                filesToShare.add(targetFile)
            }

            if (filesToShare.isEmpty()) {
                Log.e(TAG, "No valid videos to share")
                showToast("Failed to compress video(s) to under Discord's 20MB limit.")
                cleanupAndFinish()
                return
            }

            val shareUris = ArrayList(filesToShare.map { file ->
                SharedMedia.publish(this, file, "video/mp4")
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
            launchChooser(shareIntent, shareUris)

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

    private fun launchChooser(shareIntent: Intent, uris: List<Uri>, title: String? = null) {
        startActivity(SharedMedia.chooser(this, shareIntent, uris, title))
    }

    private fun cleanupAndFinish() {
        dismissNotification()
        handler.post { finish() }
    }

    private fun createNotificationChannel() = WorkService.createChannel(this)

    /** Starts the foreground work service, which owns the progress notification. */
    private fun showNotification(text: String) {
        if (workActive) {
            WorkService.update(this, text)
            return
        }
        workActive = true
        WorkService.begin(this, text)
    }

    private fun updateNotification(text: String) = WorkService.update(this, text)

    /** Ends this activity's foreground work; safe to call more than once. */
    private fun dismissNotification() {
        if (!workActive) return
        workActive = false
        WorkService.end(this)
    }

    /** Tokens scraped from the Instagram homepage that logged-out GraphQL requests need. */
    private data class IgSession(val csrfToken: String?, val lsd: String?, val cookies: String)

    /** Loads instagram.com like a browser navigation to collect its cookies and the LSD token. */
    private fun fetchSession(): IgSession? {
        try {
            val conn = URL("https://www.instagram.com/").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html")
            conn.setRequestProperty("Sec-Fetch-Dest", "document")
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate")
            conn.setRequestProperty("Sec-Fetch-Site", "none")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val setCookies = conn.headerFields
                .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                .flatMap { it.value }
            val cookies = setCookies.map { it.substringBefore(';') }.filter { it.contains('=') }
            val csrfToken = cookies.firstOrNull { it.startsWith("csrftoken=") }?.substringAfter('=')

            val html = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val lsd = Regex("\"LSD\",\\[\\],\\{[^{}]*\"token\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            if (lsd == null) Log.w(TAG, "fetchSession: no LSD token in homepage")

            return IgSession(csrfToken, lsd, cookies.joinToString("; "))
        } catch (e: Exception) {
            Log.e(TAG, "fetchSession failed: ${e.message}")
            return null
        }
    }

    private fun fetchGraphQL(shortcode: String, session: IgSession?): Pair<String, Boolean>? {
        try {
            val variables = JSONObject().apply {
                put("shortcode", shortcode)
                put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            }

            val lsd = session?.lsd
            val body = (if (lsd != null) "lsd=${URLEncoder.encode(lsd, "UTF-8")}&" else "") +
                "doc_id=$DOC_ID&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}"

            val conn = URL(GRAPHQL_ENDPOINT).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("X-IG-App-ID", IG_APP_ID)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            session?.csrfToken?.let { conn.setRequestProperty("X-CSRFToken", it) }
            if (lsd != null) conn.setRequestProperty("X-FB-LSD", lsd)
            if (!session?.cookies.isNullOrEmpty()) conn.setRequestProperty("Cookie", session!!.cookies)
            conn.setRequestProperty("Origin", "https://www.instagram.com")
            conn.setRequestProperty("Referer", "https://www.instagram.com/p/$shortcode/")
            conn.setRequestProperty("Sec-Fetch-Site", "same-origin")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
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

    private fun downloadFile(url: String, shortcode: String, extension: String): File? {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            if (conn.responseCode != 200) {
                Log.e(TAG, "Download returned ${conn.responseCode}")
                return null
            }

            // Unique per share: reusing a name gives the same content URI, and Discord can confuse
            // it with an earlier upload of that URI
            val file = File(cacheDir, "instaembed_${shortcode}_${System.currentTimeMillis()}.$extension")
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

    /**
     * Returns the file(s) to share for [input]: the original if it is already small enough,
     * one compressed file, or several parts when the video is too long to look decent in one.
     * Returns null (after telling the user) when it can't be made to fit.
     */
    private fun prepareVideo(
        input: File,
        baseName: String,
        allowSplit: Boolean,
        label: String = "video",
        quiet: Boolean = false
    ): List<File>? {
        if (input.length() <= COMPRESSION_THRESHOLD_BYTES) return listOf(input)

        val mime = AppSettings.videoMime(this)
        val ranges = if (allowSplit && AppSettings.splitLongVideos(this)) {
            VideoCompressor.planParts(VideoCompressor.probeDurationUs(input), TARGET_COMPRESSED_BYTES, mime)
        } else {
            listOf(VideoCompressor.TimeRange(0, -1))
        }

        val stamp = System.currentTimeMillis()
        val outputs = mutableListOf<File>()
        for ((i, range) in ranges.withIndex()) {
            val output = if (ranges.size == 1) {
                updateNotification("Compressing $label for Discord (<20MB)...")
                File(cacheDir, "instaembed_${baseName}_${stamp}_compressed.mp4")
            } else {
                updateNotification("Compressing part ${i + 1} of ${ranges.size}...")
                File(cacheDir, "instaembed_${baseName}_${stamp}_part${i + 1}of${ranges.size}.mp4")
            }
            val ok = try {
                VideoCompressor.compress(input, output, TARGET_COMPRESSED_BYTES, range.startUs, range.endUs, mime)
            } catch (e: Exception) {
                Log.e(TAG, "compress failed: ${e.message}", e)
                false
            }
            if (!ok) {
                output.delete()
                outputs.forEach { it.delete() }
                if (ranges.size == 1 && input.length() <= DISCORD_MAX_BYTES) {
                    Log.w(TAG, "Compression failed but original fits; sharing original")
                    return listOf(input)
                }
                Log.e(TAG, "Compression failed for $label (${input.length()} bytes)")
                if (!quiet) showToast("Failed to compress video to under Discord's 20MB limit.")
                return null
            }
            Log.i(TAG, "Compressed ${output.name} to ${output.length()} bytes")
            outputs.add(output)
        }
        return outputs
    }

    private fun startPartShares(parts: List<File>) {
        partQueue.clear()
        partQueue.addAll(parts)
        partTotal = parts.size
        dismissNotification()
        handler.post { shareNextPart() }
    }

    /** Opens the share sheet for the next queued part; the rest follow as the user returns. */
    private fun shareNextPart() {
        val part = partQueue.removeFirstOrNull() ?: return
        val partNum = partTotal - partQueue.size
        val uri = SharedMedia.publish(this, part, "video/mp4")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (partQueue.isNotEmpty()) {
            showPartNotification(partNum + 1)
        } else {
            dismissPartNotification()
        }
        showToast("Sharing part $partNum of $partTotal")
        shareLaunched = true
        launchChooser(shareIntent, listOf(uri), "Share part $partNum of $partTotal")
    }

    private fun showPartNotification(nextPart: Int) {
        try {
            createNotificationChannel()
            val intent = Intent(this, ShareActivity::class.java).apply {
                action = ACTION_NEXT_PART
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("InstaEmbed")
                .setContentText("Tap to share part $nextPart of $partTotal")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            getSystemService(NotificationManager::class.java).notify(PART_NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun dismissPartNotification() {
        try {
            getSystemService(NotificationManager::class.java).cancel(PART_NOTIFICATION_ID)
        } catch (_: Exception) {}
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
