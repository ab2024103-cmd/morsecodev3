package app.morsecode.android.core.transfer

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import app.morsecode.android.core.model.SessionState
import app.morsecode.android.di.AppServices
import kotlinx.coroutines.launch

/**
 * §3.7: a foreground service (`dataSync`) owns every active session, so
 * minimising, rotating or navigating away never interrupts a transfer. §8.2
 * says the same thing from the other side: incoming file handling is owned by
 * the engine and this service, never by a screen — a session whose incoming
 * stream is only collected while a fragment is visible will handshake, look
 * connected, and silently receive nothing.
 *
 * The notification is the session's lifeline (§6.18) and is explained in plain
 * words. Its peer name, current file, combined progress and Pause / Resume /
 * Cancel actions are filled in by Stage 9, when there is live state to show;
 * what exists here is the service that keeps the process alive.
 */
class TransferService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private var observer: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                observeQueue()
            }
        }
        // The session lives in the engine; if the platform kills us, restarting
        // an empty service would claim a transfer that is not running.
        return START_NOT_STICKY
    }

    /**
     * §6.18: the notification follows the queue. §16.5: every figure in it is
     * read from the engine's state, never from a counter kept here.
     */
    private fun observeQueue() {
        if (observer?.isActive == true) return
        observer = AppServices.engineScope.launch {
            AppServices.transferEngine.items.collect {
                // §6.13 "Notifications · Transfer progress": with the switch
                // off the service keeps its (mandatory) foreground
                // notification but stops publishing progress into it.
                if (!AppServices.prefs.notifications.value) return@collect
                val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        // Ending the service is not ending the session: the engine decides
        // that, and only a real loss pauses the queue (INV-3).
        AppServices.logStore.i("Transfer service stopped")
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val label = QueuePresentation.peerLabel(
            peerName = (AppServices.transferEngine.session.value as? SessionState.Connected)?.peerName,
            peerCount = AppServices.sessionRegistry.liveCount.value,
        )
        return TransferNotifications.build(this, label)
    }

    companion object {

        private const val NOTIFICATION_ID = TransferNotifications.NOTIFICATION_ID
        private const val ACTION_STOP = "app.morsecode.android.action.STOP_TRANSFER_SERVICE"

        /** Called when a session starts, so the process outlives the screen. */
        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }
}
