package com.instaembed.instaembed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {
    private val UPDATER_CHANNEL = "instaembed/updater"
    private val COMPRESSOR_CHANNEL = "instaembed/compressor"

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
                            val success = VideoCompressor.compress(inputFile, outputFile, targetSizeBytes)
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
                else -> result.notImplemented()
            }
        }
    }
}
