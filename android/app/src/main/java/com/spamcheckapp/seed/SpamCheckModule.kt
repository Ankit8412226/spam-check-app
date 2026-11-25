package com.spamcheckapp.seed

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/** JS bridge: `NativeModules.SpamCheck`. */
class SpamCheckModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = NAME

  @ReactMethod
  fun getConfig(promise: Promise) {
    val map = Arguments.createMap()
    map.putString("baseUrl", SeedPrefs.baseUrl(reactContext))
    map.putString("token", SeedPrefs.token(reactContext))
    promise.resolve(map)
  }

  @ReactMethod
  fun saveConfig(baseUrl: String, token: String, promise: Promise) {
    SeedPrefs.saveConfig(reactContext, baseUrl, token)
    promise.resolve(true)
  }

  /** Sends a heartbeat now (with retries) and resolves with the result. */
  @ReactMethod
  fun sendHeartbeat(promise: Promise) {
    val ctx = reactContext.applicationContext
    SeedApi.executor.execute {
      val res = SeedApi.heartbeatBlocking(ctx)
      val map = Arguments.createMap()
      map.putBoolean("ok", res.ok)
      map.putInt("httpStatus", res.httpStatus)
      map.putString("body", res.body.take(1000))
      map.putString("error", res.error)
      promise.resolve(map)
    }
  }

  @ReactMethod
  fun getStatus(promise: Promise) {
    val ctx = reactContext
    val map = Arguments.createMap()
    map.putString("simOperator", SeedApi.simOperator(ctx))
    map.putInt("sdkInt", Build.VERSION.SDK_INT)
    map.putString("androidVersion", Build.VERSION.RELEASE)
    map.putBoolean("readPhoneState", granted(Manifest.permission.READ_PHONE_STATE))
    map.putBoolean("readCallLog", granted(Manifest.permission.READ_CALL_LOG))
    map.putBoolean(
        "answerPhoneCalls",
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            granted(Manifest.permission.ANSWER_PHONE_CALLS))
    map.putBoolean("canEndCall", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    map.putBoolean(
        "postNotifications",
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS))
    map.putBoolean("accessibilityEnabled", isAccessibilityEnabled(ctx))
    map.putBoolean("accessibilityConnected", CallScreenAccessibilityService.instance != null)
    map.putBoolean("accessibilityOnDemand", AccessibilityToggle.canToggle(ctx))
    map.putBoolean("foregroundServiceRunning", SeedMonitorService.running)
    map.putBoolean("callListenerRegistered", CallMonitor.isWatcherRegistered)
    map.putBoolean("batteryOptimizationIgnored", isIgnoringBatteryOptimizations(ctx))
    map.putBoolean("configured", SeedPrefs.isConfigured(ctx))
    map.putString("lastHeartbeat", SeedPrefs.lastHeartbeatJson(ctx))
    map.putInt("rejectAfterSeconds", (CallMonitor.REJECT_AFTER_MS / 1000).toInt())
    map.putArray("keywords", Arguments.fromList(SpamKeywords.KEYWORDS))
    promise.resolve(map)
  }

  /** Resolves with the event list as a JSON string (newest first). */
  @ReactMethod
  fun getEvents(promise: Promise) {
    promise.resolve(SeedPrefs.eventsJson(reactContext))
  }

  @ReactMethod
  fun clearEvents(promise: Promise) {
    SeedPrefs.clearEvents(reactContext)
    promise.resolve(true)
  }

  /** Starts the foreground service and (re)registers the call listener. */
  @ReactMethod
  fun startMonitor(promise: Promise) {
    CallMonitor.ensureWatching(reactContext)
    promise.resolve(SeedMonitorService.start(reactContext))
  }

  @ReactMethod
  fun stopMonitor(promise: Promise) {
    SeedMonitorService.stop(reactContext)
    promise.resolve(true)
  }

  @ReactMethod
  fun openAccessibilitySettings() {
    launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
  }

  @ReactMethod
  fun openBatteryOptimizationSettings() {
    launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
  }

  @ReactMethod
  fun openAppSettings() {
    launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", reactContext.packageName, null)))
  }

  private fun launch(intent: Intent) {
    try {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      reactContext.startActivity(intent)
    } catch (e: Exception) {
      // ignore: settings screen not available on this ROM
    }
  }

  private fun granted(permission: String): Boolean =
      reactContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  companion object {
    const val NAME = "SpamCheck"

    fun isAccessibilityEnabled(ctx: Context): Boolean {
      val expected = ComponentName(ctx, CallScreenAccessibilityService::class.java)
      val enabled =
          Settings.Secure.getString(
              ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
      return enabled.split(':').any {
        ComponentName.unflattenFromString(it)?.let { c -> c == expected } == true
      }
    }

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
      val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
      return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
  }
}
