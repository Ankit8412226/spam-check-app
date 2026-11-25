package com.spamcheckapp.seed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.spamcheckapp.MainActivity

/**
 * Foreground service (type specialUse) that keeps the app process alive in the background so the
 * call-state listener stays registered, and sends a heartbeat every [HEARTBEAT_EVERY_MS].
 */
class SeedMonitorService : Service() {
  private val handler = Handler(Looper.getMainLooper())

  private val heartbeatLoop =
      object : Runnable {
        override fun run() {
          SeedApi.heartbeatAsync(this@SeedMonitorService)
          handler.postDelayed(this, HEARTBEAT_EVERY_MS)
        }
      }

  override fun onCreate() {
    super.onCreate()
    goForeground()
    running = true
    CallMonitor.ensureWatching(this)
    // on-demand mode: the screen reader stays off until a seed call rings
    if (!CallMonitor.isCapturing) AccessibilityToggle.turnOff(this)
    handler.postDelayed(heartbeatLoop, FIRST_HEARTBEAT_DELAY_MS)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // re-check registration (permissions may have been granted since)
    CallMonitor.ensureWatching(this)
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    running = false
    handler.removeCallbacks(heartbeatLoop)
    super.onDestroy()
  }

  @Suppress("DEPRECATION")
  private fun goForeground() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      nm.createNotificationChannel(
          NotificationChannel(CHANNEL_ID, "Seed phone monitor", NotificationManager.IMPORTANCE_LOW))
    }
    val open =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)
    // NOTE: keep this text free of spam keywords - it is visible while calls ring.
    val notification =
        builder
            .setContentTitle("Seed phone monitor running")
            .setContentText("Checking incoming test calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  companion object {
    private const val TAG = "SpamCheckFgs"
    private const val CHANNEL_ID = "seed_monitor"
    private const val NOTIFICATION_ID = 4201
    const val HEARTBEAT_EVERY_MS = 3L * 60 * 60 * 1000 // 3 hours
    private const val FIRST_HEARTBEAT_DELAY_MS = 60_000L

    @Volatile var running = false
      private set

    /** Starts the service; failures (e.g. background-start restrictions) are logged, not thrown. */
    fun start(ctx: Context): Boolean {
      return try {
        val i = Intent(ctx, SeedMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
        else ctx.startService(i)
        true
      } catch (e: Exception) {
        Log.w(TAG, "could not start foreground service", e)
        false
      }
    }

    fun stop(ctx: Context) {
      ctx.stopService(Intent(ctx, SeedMonitorService::class.java))
    }
  }
}
