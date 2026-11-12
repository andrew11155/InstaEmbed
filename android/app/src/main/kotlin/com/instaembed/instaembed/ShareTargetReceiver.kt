package com.instaembed.instaembed

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat

/**
 * Told by the share sheet which app the user picked. Gives that app its own read access to the
 * shared files: the grant carried by the share intent can end when ShareActivity finishes, while
 * Discord may still be uploading from our content URI in the background.
 */
class ShareTargetReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_URIS = "com.instaembed.instaembed.SHARED_URIS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val chosen = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            ?: return
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java) ?: return
        if (chosen.packageName == context.packageName) return
        try {
            for (uri in uris) {
                context.grantUriPermission(chosen.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Log.i("InstaEmbed", "Granted ${uris.size} file(s) to ${chosen.packageName}")
        } catch (e: Exception) {
            Log.w("InstaEmbed", "grantUriPermission failed: ${e.message}")
        }
    }
}
