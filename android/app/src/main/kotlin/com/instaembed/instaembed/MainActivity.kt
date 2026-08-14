package com.instaembed.instaembed

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "instaembed/updater"

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
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
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
    }
}
