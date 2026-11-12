package com.instaembed.instaembed

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Turns a finished file into the URI handed to the share sheet.
 *
 * Files are published to MediaStore (Movies/InstaEmbed or Pictures/InstaEmbed) rather than shared
 * from our own FileProvider. Discord keeps reading a shared file while it converts and uploads in
 * the background, and a FileProvider only answers while InstaEmbed's process runs; once Android or
 * Samsung froze the backgrounded app, Discord's next read never returned and the upload sat on
 * "sending" forever. MediaStore is served by the system media provider, which is never frozen
 * (the same path a gallery share uses). Published copies are deleted after a couple of hours.
 */
object SharedMedia {
    private const val TAG = "InstaEmbed"
    private const val FOLDER = "InstaEmbed"
    // Long enough for any upload to finish, short enough that the gallery doesn't fill up
    private const val KEEP_MS = 2L * 60 * 60 * 1000

    /**
     * Returns a shareable URI for [file]: a MediaStore copy on Android 10+ (the cache copy is
     * then deleted), otherwise — or if publishing fails — a FileProvider URI for the file itself.
     */
    fun publish(context: Context, file: File, mimeType: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val uri = insert(context, file, mimeType)
                file.delete()
                return uri
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore publish failed, falling back to FileProvider: ${e.message}")
            }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Wraps [shareIntent] in a share sheet. Once the user picks an app, [ShareTargetReceiver]
     * grants that app lasting read access, which matters on the FileProvider fallback path.
     */
    fun chooser(context: Context, shareIntent: Intent, uris: List<Uri>, title: String? = null): Intent {
        val callback = Intent(context, ShareTargetReceiver::class.java)
            .putParcelableArrayListExtra(ShareTargetReceiver.EXTRA_URIS, ArrayList(uris))
        // Mutable so the system can add EXTRA_CHOSEN_COMPONENT; unique request code per share
        val pending = PendingIntent.getBroadcast(
            context, (System.currentTimeMillis() and 0x7fffffff).toInt(), callback,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        return Intent.createChooser(shareIntent, title, pending.intentSender)
    }

    /** Publishes [file] and builds the share sheet for it (used by the Flutter screens). */
    fun shareFileIntent(context: Context, file: File, mimeType: String): Intent {
        val uri = publish(context, file, mimeType)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return chooser(context, send, listOf(uri))
    }

    private fun insert(context: Context, file: File, mimeType: String): Uri {
        val isVideo = mimeType.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val dir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("insert returned null")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("openOutputStream returned null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        Log.i(TAG, "Published ${file.name} (${file.length()} bytes) to $uri")
        return uri
    }

    /** Deletes our published copies that are old enough for any upload to have finished. */
    fun deleteStale(context: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val cutoffSec = (System.currentTimeMillis() - KEEP_MS) / 1000
        for (collection in listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        )) {
            try {
                // Without media read permission only our own inserts are visible, but filter by
                // owner anyway so nothing the user saved there by hand is ever touched
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                        "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                        "${MediaStore.MediaColumns.DATE_ADDED} < ?",
                    arrayOf("%/$FOLDER/%", context.packageName, cutoffSec.toString()),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        try {
                            context.contentResolver.delete(uri, null, null)
                        } catch (e: Exception) {
                            // e.g. ownership lost after a reinstall; leave it for the user
                            Log.w(TAG, "Could not delete $uri: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stale media cleanup failed: ${e.message}")
            }
        }
    }
}
