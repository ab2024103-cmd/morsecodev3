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
    private var stoppingNormally = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppingNormally = true
                AppServices.prefs.clearTransferMarker()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            else -> {
                // §14.1: leave durable evidence while active work is owned by
                // this service. If an OEM kills us after the task moved to the
                // background, the next process can explain *why* it warns.
                AppServices.prefs.markTransferActive()
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
            AppServices.transferEngine.items.collect { items ->
                // §6.13 "Notifications · Transfer progress": with the switch
                // off the service keeps its (mandatory) foreground
                // notification but stops publishing progress into it.
                val active = items.any {
                    it.state == app.morsecode.android.core.model.TransferState.IN_PROGRESS ||
                        it.state == app.morsecode.android.core.model.TransferState.QUEUED
                }
                if (items.isNotEmpty() && !active) {
                    // A completed, deliberately paused or cleanly ended batch
                    // is not a background-shaped death. Clear the marker and
                    // stop the foreground service instead of leaving a ghost
                    // notification (§14.3, §14.5). A fresh connected session
                    // has no rows yet, and must remain available for a batch.
                    stoppingNormally = true
                    AppServices.prefs.clearTransferMarker()
                    stopSelfSafely()
                    return@collect
                }
                if (!AppServices.prefs.notifications.value) return@collect
                val manager = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
                manager?.notify(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // This is the best platform signal that an active transfer has moved
        // out of sight. Do not stop it — §3.7 says it survives minimising —
        // but leave evidence in case the process subsequently vanishes.
        AppServices.prefs.markTransferBackgrounded()
        AppServices.logStore.i("Transfer moved to background")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        // A normal End / terminal queue clears the marker above. A destructive
        // process death often does not call onDestroy at all; if it does after
        // onTaskRemoved we deliberately retain the marker for §14.1's warning.
        if (stoppingNormally) AppServices.prefs.clearTransferMarker()
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
