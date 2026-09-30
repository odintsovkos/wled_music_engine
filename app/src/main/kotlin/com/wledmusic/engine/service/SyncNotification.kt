package com.wledmusic.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.wledmusic.engine.R
import com.wledmusic.engine.session.CaptureStatus
import com.wledmusic.engine.session.SessionState
import com.wledmusic.engine.session.TransportStatus
import com.wledmusic.engine.ui.MainActivity

object SyncNotification {
    const val CHANNEL_ID = "sync"
    const val ONGOING_ID = 1
    const val STOPPED_ID = 2

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun statusText(context: Context, state: SessionState): String {
        val capture = context.getString(captureLabel(state.capture))
        val transport = when (state.transport) {
            TransportStatus.Idle -> null
            TransportStatus.Sending -> context.getString(R.string.transport_sending_to, state.target ?: "")
            TransportStatus.NoNetwork -> context.getString(R.string.transport_no_network)
            is TransportStatus.Error -> context.getString(R.string.transport_error)
        }
        return listOfNotNull(capture, transport).joinToString(" · ")
    }

    fun captureLabel(status: CaptureStatus): Int = when (status) {
        CaptureStatus.INACTIVE -> R.string.capture_inactive
        CaptureStatus.ACTIVE -> R.string.capture_active
        CaptureStatus.SILENCE -> R.string.capture_silence
        CaptureStatus.NO_PERMISSION -> R.string.capture_no_permission
        CaptureStatus.STOPPED_BY_SYSTEM -> R.string.capture_stopped_by_system
    }

    fun ongoing(context: Context, state: SessionState): Notification {
        val stop = PendingIntent.getService(
            context, 0, SyncService.stopIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(statusText(context, state))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.action_stop), stop).build())
            .build()
    }

    /** Итоговое уведомление после остановки системой: не постоянное, ведёт в приложение. */
    fun stoppedBySystem(context: Context): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_stopped_by_system))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
