package com.instaembed.instaembed

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {
    private const val TAG = "InstaEmbedUpdater"
    private const val REPO = "andrew11155/InstaEmbed"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val CHANNEL_ID = "instaembed_updates"
    private const val NOTIFICATION_ID = 2001
    private const val PREFS_NAME = "instaembed_updater"
    private const val PREF_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

    data class UpdateInfo(val version: String, val apkUrl: String)

    /** Throttled background check, safe to call on every app open / share. */
    fun checkForUpdateAsync(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0)
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return

        Thread {
            val info = runCatching { fetchLatestIfNewer(appContext) }.getOrNull()
            // Only stamp the throttle once we actually know the result, so a
            // failed/skipped check doesn't block a real check for 12h.
            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            if (info != null) notifyUpdateAvailable(appContext, info.version, info.apkUrl)
        }.start()
    }

    /** On-demand check bypassing the throttle. Must be called off the main thread. */
    fun checkForUpdateNowBlocking(context: Context): UpdateInfo? {
        val appContext = context.applicationContext
        val info = fetchLatestIfNewer(appContext)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        if (info != null) notifyUpdateAvailable(appContext, info.version, info.apkUrl)
        return info
    }

    private fun fetchLatestIfNewer(context: Context): UpdateInfo? {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        if (conn.responseCode != 200) {
            Log.i(TAG, "Update check returned ${conn.responseCode}")
            return null
        }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val tagName = json.optString("tag_name", "").removePrefix("v")
        if (tagName.isEmpty()) return null

        val currentVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: return null

        if (!isNewer(tagName, currentVersion)) return null

        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                val apkUrl = asset.optString("browser_download_url")
                return UpdateInfo(tagName, apkUrl)
            }
        }
        Log.i(TAG, "Latest release $tagName has no APK asset")
        return null
    }

    private fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.split("+").first().split(".").map { it.toIntOrNull() ?: 0 }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private fun notifyUpdateAvailable(context: Context, version: String, apkUrl: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "InstaEmbed Updates", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifies when a new InstaEmbed version is available" }
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(context, UpdateActivity::class.java).apply {
            putExtra("apkUrl", apkUrl)
            putExtra("version", version)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("InstaEmbed update available")
            .setContentText("Version $version is ready to install. Tap to update.")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val canNotify = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            nm.notify(NOTIFICATION_ID, notification)
        }
    }
}
