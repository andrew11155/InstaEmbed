package com.instaembed.instaembed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream

class MainActivity : FlutterActivity() {
    private val UPDATER_CHANNEL = "instaembed/updater"
    private val COMPRESSOR_CHANNEL = "instaembed/compressor"
    private val SETTINGS_CHANNEL = "instaembed/settings"

    companion object {
        private const val REQUEST_PICK_VIDEO = 2002
    }

    private var pickVideoResult: MethodChannel.Result? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_VIDEO) {
            val result = pickVideoResult
            pickVideoResult = null
            if (result == null) return

            if (resultCode != RESULT_OK || data == null) {
                result.success(null)
                return
            }

            val uri: Uri? = data.data ?: data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri == null) {
                result.success(null)
                return
            }

            Thread {
                try {
                    val fileName = getFileName(uri) ?: "video.mp4"
                    val extension = fileName.substringAfterLast('.', "mp4")
                    val tempFile = File(cacheDir, "instaembed_picked_${System.currentTimeMillis()}.${extension}")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    val size = tempFile.length()
                    val path = tempFile.absolutePath
                    runOnUiThread {
                        result.success(mapOf(
                            "path" to path,
                            "size" to size,
                            "name" to fileName
                        ))
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to process picked video: ${e.message}", e)
                    runOnUiThread {
                        result.error("PICK_FAILED", e.message, null)
                    }
                }
            }.start()
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
                Log.w("MainActivity", "Failed to query filename: ${e.message}")
            }
        }
        return uri.lastPathSegment
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
        UpdateManager.checkForUpdateAsync(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, UPDATER_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkForUpdateNow" -> {
                    Thread {
                        try {
                            val info = UpdateManager.checkForUpdateNowBlocking(this)
                            runOnUiThread {
                                if (info != null) {
                                    result.success(mapOf("available" to true, "version" to info.version))
                                } else {
                                    result.success(mapOf("available" to false))
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { result.error("CHECK_FAILED", e.message, null) }
                        }
                    }.start()
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SETTINGS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getSettings" -> result.success(AppSettings.toMap(this))
                "setBool" -> {
                    val key = call.argument<String>("key")
                    val value = call.argument<Boolean>("value")
                    if (key == null || value == null) {
                        result.error("INVALID_ARGS", "key and value are required", null)
                    } else {
                        AppSettings.setBoolean(this, key, value)
                        result.success(null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, COMPRESSOR_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "compressVideo" -> {
                    val inputPath = call.argument<String>("inputPath")
                    val shortcode = call.argument<String>("shortcode") ?: "temp"
                    val targetSizeBytes = (call.argument<Number>("targetSizeBytes")?.toLong()) ?: (18L * 1024 * 1024)

                    if (inputPath == null) {
                        result.error("INVALID_ARGS", "inputPath is required", null)
                        return@setMethodCallHandler
                    }

                    Thread {
                        try {
                            val inputFile = File(inputPath)
                            val outputFile = File(cacheDir, "instaembed_${shortcode}_compressed.mp4")
                            val success = VideoCompressor.compress(
                                inputFile, outputFile, targetSizeBytes,
                                videoMime = AppSettings.videoMime(this)
                            )
                            runOnUiThread {
                                if (success && outputFile.exists() && outputFile.length() > 0) {
                                    result.success(mapOf(
                                        "success" to true,
                                        "path" to outputFile.absolutePath,
                                        "size" to outputFile.length()
                                    ))
                                } else {
                                    result.success(mapOf(
                                        "success" to false,
                                        "path" to inputFile.absolutePath,
                                        "size" to inputFile.length()
                                    ))
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                result.error("COMPRESS_FAILED", e.message, null)
                            }
                        }
                    }.start()
                }
                "pickVideo" -> {
                    if (pickVideoResult != null) {
                        result.error("ALREADY_ACTIVE", "Video picker is already running", null)
                        return@setMethodCallHandler
                    }
                    pickVideoResult = result
                    try {
                        val pickIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "video/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*"))
                        }
                        startActivityForResult(Intent.createChooser(pickIntent, "Select Video"), REQUEST_PICK_VIDEO)
                    } catch (e: Exception) {
                        pickVideoResult = null
                        result.error("LAUNCH_FAILED", e.message, null)
                    }
                }
                else -> result.notImplemented()
            }
        }
    }
}
