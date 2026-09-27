package app.morsecode.android.core.transfer

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.morsecode.android.MainActivity
import app.morsecode.android.R
import app.morsecode.android.core.model.TransferState
import app.morsecode.android.core.util.Ids
import app.morsecode.android.di.AppServices

/**
 * §6.18 THE TRANSFER NOTIFICATION.
 *
 * "shows peer (or "3 phones"), current file, combined progress and Pause /
 * Resume / Cancel actions, and is explained in plain words the first time it
 * appears: it exists to keep the transfer alive, dismissing or force-stopping
 * interrupts it, and it disappears on its own."
 *
 * Every figure comes from [QueuePresentation], which reads the queue — §16.5
 * forbids a side-counter, and this is the surface where one would be most
 * tempting.
 */
object TransferNotifications {

    const val NOTIFICATION_ID = 1001

    private const val ACTION_PAUSE = "app.morsecode.android.action.TRANSFER_PAUSE"
    private const val ACTION_RESUME = "app.morsecode.android.action.TRANSFER_RESUME"
    private const val ACTION_CANCEL = "app.morsecode.android.action.TRANSFER_CANCEL"
    private const val PREF = "morsecode.notifications"
    private const val KEY_EXPLAINED = "transfer_explained"

    fun build(context: Context, peerLabel: String): Notification {
        val items = AppServices.transferEngine.items.value
        val content = QueuePresentation.notification(items, peerLabel)
        val anyPaused = items.any { it.state == TransferState.PAUSED }

        val builder = NotificationCompat.Builder(context, Ids.CHANNEL_TRANSFER)
            .setSmallIcon(R.drawable.ic_send)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)

        if (content.showProgress) {
            builder.setProgress(100, content.progressPercent, false)
        }

        // The first time it appears, it explains itself in plain words (§6.18).
        if (!hasExplained(context)) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_transfer_explainer)),
            )
            markExplained(context)
        }

        if (anyPaused) {
            builder.addAction(0, context.getString(R.string.notification_resume), action(context, ACTION_RESUME))
        } else {
            builder.addAction(0, context.getString(R.string.action_pause), action(context, ACTION_PAUSE))
        }
        builder.addAction(0, context.getString(R.string.action_cancel), action(context, ACTION_CANCEL))
        return builder.build()
    }

    private fun hasExplained(context: Context): Boolean = context
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_EXPLAINED, false)

    private fun markExplained(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXPLAINED, true)
            .apply()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        flags(),
    )

    private fun action(context: Context, action: String): PendingIntent {
        val intent = Intent(context, TransferActionReceiver::class.java)
        intent.action = action
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags())
    }

    private fun flags(): Int = if (Build.VERSION.SDK_INT >= 23) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    /**
     * §21.2 case 6: cancelling from the notification must work exactly like
     * cancelling from the screen, so these actions go through the engine's own
     * methods rather than a shortcut of their own.
     */
    class TransferActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val engine = AppServices.transferEngine
            val items = engine.items.value
            when (intent.action) {
                ACTION_PAUSE -> items.filter { it.state == TransferState.IN_PROGRESS || it.state == TransferState.QUEUED }
                    .forEach { engine.pause(it.id) }
                ACTION_RESUME -> items.filter { it.state == TransferState.PAUSED }
                    .forEach { engine.resume(it.id) }
                ACTION_CANCEL -> items.filterNot { it.state.isTerminal }
                    .forEach { engine.cancel(it.id) }
            }
        }
    }
}
