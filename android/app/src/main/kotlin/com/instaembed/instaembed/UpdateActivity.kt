package com.instaembed.instaembed

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateActivity : Activity() {

    private val TAG = "InstaEmbedUpdater"
    private val CHANNEL_ID = "instaembed_progress"
    private val NOTIFICATION_ID = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkUrl = intent?.getStringExtra("apkUrl")
        val version = intent?.getStringExtra("version") ?: "latest"
        if (apkUrl == null) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            runOnUiThread {
                Toast.makeText(
                    this,
                    "Allow InstaEmbed to install updates, then tap the update notification again",
                    Toast.LENGTH_LONG
                ).show()
            }
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            })
            finish()
            return
        }

        Thread { downloadAndInstall(apkUrl, version) }.start()
    }

    private fun downloadAndInstall(apkUrl: String, version: String) {
        try {
            showNotification("Downloading update v$version...")

            val conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            if (conn.responseCode != 200) {
                Log.e(TAG, "Update download returned ${conn.responseCode}")
                dismissNotification()
                finish()
                return
            }

            val file = File(cacheDir, "instaembed_update.apk")
            conn.inputStream.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }

            dismissNotification()

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Update failed: ${e.message}", e)
            dismissNotification()
        } finally {
            finish()
        }
    }

    private fun showNotification(text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "InstaEmbed", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("InstaEmbed")
                .setContentText(text)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
    }

    private fun dismissNotification() {
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }
}
