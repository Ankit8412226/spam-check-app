package com.spamcheckapp.seed

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Listens for call-state changes and forwards them to [CallMonitor].
 *
 *  - API 31+: TelephonyCallback.CallStateListener (no number is delivered here)
 *  - API < 31: PhoneStateListener (delivers the number when READ_CALL_LOG is granted)
 *  - All APIs: a runtime-registered ACTION_PHONE_STATE_CHANGED receiver, which carries
 *    EXTRA_INCOMING_NUMBER when READ_PHONE_STATE + READ_CALL_LOG are granted.
 *
 * Both sources may fire for the same transition; [CallMonitor] is idempotent.
 * Must be created / registered on the main thread.
 */
class CallStateWatcher(private val ctx: Context) {
  private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
  private var listenerRegistered = false
  private var receiverRegistered = false
  private var telephonyCallback: Any? = null // TelephonyCallback on API 31+
  private var phoneStateListener: PhoneStateListener? = null

  val isRegistered: Boolean
    get() = listenerRegistered || receiverRegistered

  fun register() {
    if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
        PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "READ_PHONE_STATE not granted yet; call detection disabled")
      return
    }
    if (!listenerRegistered) {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) registerCallback()
        else registerLegacyListener()
        listenerRegistered = true
      } catch (e: Exception) {
        Log.w(TAG, "call state listener registration failed", e)
      }
    }
    if (!receiverRegistered) {
      try {
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
          ctx.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
      } catch (e: Exception) {
        Log.w(TAG, "phone state receiver registration failed", e)
      }
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun registerCallback() {
    val cb =
        object : TelephonyCallback(), TelephonyCallback.CallStateListener {
          override fun onCallStateChanged(state: Int) = handleState(state, null)
        }
    tm.registerTelephonyCallback(ctx.mainExecutor, cb)
    telephonyCallback = cb
  }

  @Suppress("DEPRECATION")
  private fun registerLegacyListener() {
    val l =
        object : PhoneStateListener() {
          @Deprecated("Deprecated in Java")
          override fun onCallStateChanged(state: Int, phoneNumber: String?) =
              handleState(state, phoneNumber)
        }
    tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    phoneStateListener = l
  }

  private val receiver =
      object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
          val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
          when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> CallMonitor.onRinging(number)
            TelephonyManager.EXTRA_STATE_OFFHOOK,
            TelephonyManager.EXTRA_STATE_IDLE -> {
              CallMonitor.onNumber(number)
              CallMonitor.onCallEnded()
            }
          }
        }
      }

  private fun handleState(state: Int, number: String?) {
    when (state) {
      TelephonyManager.CALL_STATE_RINGING -> CallMonitor.onRinging(number)
      TelephonyManager.CALL_STATE_OFFHOOK,
      TelephonyManager.CALL_STATE_IDLE -> CallMonitor.onCallEnded()
    }
  }

  companion object {
    private const val TAG = "SpamCheckWatcher"
  }
}
