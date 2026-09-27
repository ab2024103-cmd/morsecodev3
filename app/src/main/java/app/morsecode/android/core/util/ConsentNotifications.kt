package app.morsecode.android.core.util

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.morsecode.android.MainActivity
import app.morsecode.android.R
import app.morsecode.android.di.AppServices

/**
 * §6.16 [GAP]: "Both dialogs also raise a heads-up notification when the app is
 * backgrounded, with Accept/Reject actions."
 *
 * The transport is suspended inside `ConsentRequests.ask` either way, so a
 * request answered from the notification shade is the same gate as the dialog
 * — nothing crosses the wire before Accept (§17.2).
 */
object ConsentNotifications {

    private const val NOTIFICATION_ID = 2001
    private const val EXTRA_ID = "app.morsecode.android.extra.CONSENT_ID"
    private const val ACTION_ACCEPT = "app.morsecode.android.action.CONSENT_ACCEPT"
    private const val ACTION_REJECT = "app.morsecode.android.action.CONSENT_REJECT"

    fun raise(context: Context, request: app.morsecode.android.core.network.ConsentRequests.Request) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val builder = NotificationCompat.Builder(context, Ids.CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_receive)
            .setContentTitle(request.title)
            .setContentText(request.detail)
            // High importance so it is a heads-up, not a silent row (§6.18).
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(
                0,
                context.getString(R.string.consent_reject),
                action(context, ACTION_REJECT, request.id),
            )
            .addAction(
                0,
                context.getString(R.string.consent_accept),
                action(context, ACTION_ACCEPT, request.id),
            )

        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        flags(),
    )

    private fun action(context: Context, action: String, requestId: String): PendingIntent {
        val intent = Intent(context, ConsentActionReceiver::class.java)
        intent.action = action
        intent.putExtra(EXTRA_ID, requestId)
        return PendingIntent.getBroadcast(context, requestId.hashCode(), intent, flags())
    }

    private fun flags(): Int = if (Build.VERSION.SDK_INT >= 23) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    /** Answers the SAME gate the dialog answers; there is only one broker. */
    class ConsentActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra(EXTRA_ID) ?: return
            val accepted = intent.action == ACTION_ACCEPT
            AppServices.consentRequests.answer(id, accepted)
            clear(context)
        }
    }
}
