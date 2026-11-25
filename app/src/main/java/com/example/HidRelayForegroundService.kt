package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class HidRelayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "hid_relay_foreground_channel"
        private const val NOTIFICATION_ID = 9876
        private const val EXTRA_STATUS_TEXT = "extra_status_text"
        private const val ACTION_SYNC_WAKE_LOCK = "com.example.action.SYNC_WAKE_LOCK"

        @Volatile
        private var activeInstance: HidRelayForegroundService? = null

        @Volatile
        private var isServiceRunning = false

        @Volatile
        private var isHidConnected = false

        @Volatile
        private var isWacomRunning = false

        @Volatile
        private var isMouseRunning = false

        fun hasRequiredPermissions(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return false
                }
            }
            return true
        }

        fun start(context: Context, statusText: String = "Bluetooth HID keyboard active in background") {
            if (!hasRequiredPermissions(context)) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, HidRelayForegroundService::class.java).apply {
                putExtra(EXTRA_STATUS_TEXT, statusText)
            }
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Exception) {
            }
        }

        fun updateStatus(context: Context, statusText: String) {
            val instance = activeInstance
            if (instance != null && isServiceRunning) {
                instance.refreshNotification(statusText)
                return
            }
            if (!isServiceRunning) return
            start(context, statusText)
        }

        fun setHidConnected(context: Context, connected: Boolean) {
            isHidConnected = connected
            syncWakeLock(context)
        }

        fun setWacomRunning(context: Context, running: Boolean) {
            isWacomRunning = running
            syncWakeLock(context)
        }

        fun setMouseRunning(context: Context, running: Boolean) {
            isMouseRunning = running
            syncWakeLock(context)
        }

        private fun syncWakeLock(context: Context) {
            val instance = activeInstance
            if (instance != null && isServiceRunning) {
                instance.updateCpuWakeLock(
                    shouldHold = isHidConnected || isWacomRunning || isMouseRunning
                )
                return
            }
            if (!isServiceRunning) return
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, HidRelayForegroundService::class.java).apply {
                action = ACTION_SYNC_WAKE_LOCK
            }
            try {
                appCtx.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            isHidConnected = false
            isWacomRunning = false
            isMouseRunning = false
            isServiceRunning = false
            activeInstance?.releaseWakeLock()
            try {
                appCtx.stopService(Intent(appCtx, HidRelayForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val wakeLockGuard = Any()
    @Volatile
    private var lastStatusText: String = "Bluetooth HID keyboard active in background"

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasRequiredPermissions(this)) {
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action != ACTION_SYNC_WAKE_LOCK || !isServiceRunning) {
            ensureNotificationChannel()
            val newStatus = intent?.getStringExtra(EXTRA_STATUS_TEXT)
            if (!newStatus.isNullOrBlank()) {
                lastStatusText = newStatus
            }
            val notification = buildNotification(lastStatusText)

            try {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    0
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
                isServiceRunning = true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    isServiceRunning = false
                }
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        updateCpuWakeLock(shouldHold = isHidConnected || isWacomRunning || isMouseRunning)
        return START_STICKY
    }

    override fun onDestroy() {
        isServiceRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun refreshNotification(statusText: String) {
        if (statusText.isBlank()) return
        lastStatusText = statusText
        try {
            ensureNotificationChannel()
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildNotification(statusText))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun updateCpuWakeLock(shouldHold: Boolean) {
        synchronized(wakeLockGuard) {
            if (shouldHold) {
                if (wakeLock == null) {
                    val pm = getSystemService(PowerManager::class.java) ?: return
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "HidForwarder::HidRelayCpuLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                try {
                    if (wakeLock?.isHeld == false) {
                        wakeLock?.acquire()
                    }
                } catch (_: Exception) {
                }
            } else {
                releaseWakeLockLocked()
            }
        }
    }

    private fun releaseWakeLock() {
        synchronized(wakeLockGuard) {
            releaseWakeLockLocked()
        }
    }

    private fun releaseWakeLockLocked() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "HID Relay Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps Bluetooth HID keyboard active in background and lock screen"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }
}
