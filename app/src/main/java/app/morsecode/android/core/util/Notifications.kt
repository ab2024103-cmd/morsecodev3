package app.morsecode.android.core.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.morsecode.android.R

/**
 * §6.18 notification channels, created once at process start.
 *
 * Four channels: Transfers (low, ongoing), Requests (high, heads-up),
 * WebShare (low, ongoing), Playback (low, media). The ids come from [Ids] and
 * are never typed twice (§1.1.1).
 *
 * Nothing posts to them yet — the transfer service, the consent heads-up and
 * the WebShare server arrive in later stages. Creating them here is safe and
 * idempotent: a channel the user has since reconfigured is left exactly as
 * they set it.
 */
object Notifications {

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        create(manager, Ids.CHANNEL_TRANSFER, context.getString(R.string.channel_transfers), low())
        create(manager, Ids.CHANNEL_REQUESTS, context.getString(R.string.channel_requests), high())
        create(manager, Ids.CHANNEL_WEBSHARE, context.getString(R.string.channel_webshare), low())
        create(manager, Ids.CHANNEL_PLAYBACK, context.getString(R.string.channel_playback), low())
    }

    private fun low(): Int = NotificationManager.IMPORTANCE_LOW

    private fun high(): Int = NotificationManager.IMPORTANCE_HIGH

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun create(manager: NotificationManager, id: String, name: String, importance: Int) {
        val channel = NotificationChannel(id, name, importance)
        // Progress and session notifications are silent; only Requests buzzes.
        channel.setShowBadge(importance == NotificationManager.IMPORTANCE_HIGH)
        manager.createNotificationChannel(channel)
    }
}
