package com.instaembed.instaembed

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import java.io.File

/**
 * Debug-only: runs VideoCompressor against a file in the app's files dir.
 *
 * adb shell am start -n com.instaembed.instaembed/.CompressTestActivity \
 *   --es input in.mp4 --es output out.mp4 [--el target BYTES] [--el start US --el end US]
 *   [--es mime video/hevc] [--ez plan true]
 */
class CompressTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dir = filesDir
        val input = File(dir, intent.getStringExtra("input") ?: "in.mp4")
        val output = File(dir, intent.getStringExtra("output") ?: "out.mp4")
        val target = intent.getLongExtra("target", 18L * 1024 * 1024)
        val startUs = intent.getLongExtra("start", 0L)
        val endUs = intent.getLongExtra("end", -1L)
        val mime = intent.getStringExtra("mime") ?: VideoCompressor.MIME_AVC
        if (intent.getBooleanExtra("plan", false)) {
            val parts = VideoCompressor.planParts(VideoCompressor.probeDurationUs(input), target, mime)
            Log.i(TAG, "PLAN $parts")
        }
        Thread {
            val start = System.currentTimeMillis()
            val ok = try {
                VideoCompressor.compress(input, output, target, startUs, endUs, mime)
            } catch (e: Throwable) {
                Log.e(TAG, "test compress threw", e)
                false
            }
            Log.i(TAG, "RESULT ok=$ok size=${output.length()} ms=${System.currentTimeMillis() - start}")
            runOnUiThread { finish() }
        }.start()
    }

    companion object {
        private const val TAG = "CompressTest"
    }
}
