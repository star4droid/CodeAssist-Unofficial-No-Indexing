package com.tyron.code.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.tyron.code.R
import kotlinx.coroutines.*

class ApkBuildService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CodeAssist::Build")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectPath = intent?.getStringExtra("projectPath") ?: run {
            stopSelf()
            return START_NOT_STICKY
        }

        // 1.  Promote to foreground
        val notification = buildForegroundNotification("Building APK…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2.  Keep CPU awake
        wakeLock.acquire(30 * 60 * 1000L /* 30 min */)

        // 3.  Do the build
        scope.launch {
            try {
                runBuild(projectPath)
                updateNotification("Build finished ✓")
            } catch (t: Throwable) {
                updateNotification("Build failed ❌")
            } finally {
                wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun runBuild(projectPath: String) {
        // TODO: hook your existing Gradle/D8/R8 build here
        // For now we just sleep to simulate work
        delay(10_000)
    }

    private fun buildForegroundNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_build)
            .setContentTitle("CodeAssist Builder")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val n = buildForegroundNotification(text)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, n)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Builder",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "CodeAssist build progress" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "codeassist_builder"
        private const val NOTIFICATION_ID = 1001
    }
}
