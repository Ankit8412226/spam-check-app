package com.spamcheckapp.seed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the monitor after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    when (intent.action) {
      Intent.ACTION_BOOT_COMPLETED,
      Intent.ACTION_MY_PACKAGE_REPLACED -> {
        SeedMonitorService.start(context)
        SeedApi.heartbeatAsync(context)
      }
    }
  }
}
