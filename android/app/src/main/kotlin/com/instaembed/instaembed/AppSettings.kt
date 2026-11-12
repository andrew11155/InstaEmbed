package com.instaembed.instaembed

import android.content.Context

/** User-facing compression settings, shared by the native share flow and the Flutter UI. */
object AppSettings {
    private const val PREFS = "instaembed_settings"
    const val KEY_SPLIT_LONG_VIDEOS = "split_long_videos"
    const val KEY_USE_HEVC = "use_hevc"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun splitLongVideos(context: Context): Boolean = prefs(context).getBoolean(KEY_SPLIT_LONG_VIDEOS, true)

    // H.265 gives noticeably better quality at the same size; Discord plays it since mid-2024
    fun useHevc(context: Context): Boolean = prefs(context).getBoolean(KEY_USE_HEVC, true)

    fun videoMime(context: Context): String =
        if (useHevc(context) && VideoCompressor.hasHardwareEncoder(VideoCompressor.MIME_HEVC)) {
            VideoCompressor.MIME_HEVC
        } else {
            VideoCompressor.MIME_AVC
        }

    fun toMap(context: Context): Map<String, Any> = mapOf(
        KEY_SPLIT_LONG_VIDEOS to splitLongVideos(context),
        KEY_USE_HEVC to useHevc(context),
        "hevc_supported" to VideoCompressor.hasHardwareEncoder(VideoCompressor.MIME_HEVC)
    )

    fun setBoolean(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }
}
