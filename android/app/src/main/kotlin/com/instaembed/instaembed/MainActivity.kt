package com.instaembed.instaembed

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.instaembed/share"
    private var pendingShareText: String? = null
    private var channel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "getInitialShare" -> {
                    val text = pendingShareText
                    pendingShareText = null
                    result.success(text)
                }
                "finishActivity" -> {
                    runOnUiThread { finish() }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        if (pendingShareText != null) {
            channel!!.invokeMethod("onSharedText", pendingShareText)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = intent?.getStringExtra("shared_text")
            ?: intent?.let {
                if (it.action == Intent.ACTION_SEND && it.type == "text/plain") {
                    it.getStringExtra(Intent.EXTRA_TEXT)
                } else null
            }

        if (text != null) {
            pendingShareText = text
            channel?.invokeMethod("onSharedText", text)
        }
    }
}
