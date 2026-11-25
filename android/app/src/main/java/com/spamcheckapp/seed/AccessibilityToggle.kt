package com.spamcheckapp.seed

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * On-demand mode: the call-screen reader is turned on only while one of OUR seed calls rings, and
 * off again right after, so banking / UPI apps never see an accessibility service on this phone.
 *
 * Needs WRITE_SECURE_SETTINGS, which only adb can grant (once, survives app updates):
 *   adb shell pm grant com.spamcheckapp android.permission.WRITE_SECURE_SETTINGS
 * Without it the app works as before: the user keeps the service on in Accessibility settings.
 */
object AccessibilityToggle {
  private const val TAG = "SpamCheckToggle"

  fun canToggle(ctx: Context): Boolean =
      ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
          PackageManager.PERMISSION_GRANTED

  private fun ours(ctx: Context): ComponentName =
      ComponentName(ctx, CallScreenAccessibilityService::class.java)

  private fun enabledList(ctx: Context): MutableList<String> =
      (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
          .split(':')
          .filter { it.isNotBlank() }
          .toMutableList()

  private fun isOurs(ctx: Context, entry: String): Boolean =
      ComponentName.unflattenFromString(entry) == ours(ctx)

  /** Turns our service on (no-op without the permission or when already on). */
  fun turnOn(ctx: Context): Boolean {
    if (!canToggle(ctx)) return false
    return try {
      val list = enabledList(ctx)
      if (list.none { isOurs(ctx, it) }) {
        list.add(ours(ctx).flattenToString())
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Log.i(TAG, "call-screen reader turned on")
      }
      true
    } catch (e: Exception) {
      Log.w(TAG, "could not turn the call-screen reader on", e)
      false
    }
  }

  /** Turns our service off, leaving any other accessibility service as it was. */
  fun turnOff(ctx: Context): Boolean {
    if (!canToggle(ctx)) return false
    return try {
      val list = enabledList(ctx)
      if (list.any { isOurs(ctx, it) }) {
        val rest = list.filterNot { isOurs(ctx, it) }
        val cr = ctx.contentResolver
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, rest.joinToString(":"))
        if (rest.isEmpty()) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Log.i(TAG, "call-screen reader turned off")
      }
      true
    } catch (e: Exception) {
      Log.w(TAG, "could not turn the call-screen reader off", e)
      false
    }
  }
}
