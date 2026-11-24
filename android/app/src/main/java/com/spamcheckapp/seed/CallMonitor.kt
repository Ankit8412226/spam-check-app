package com.spamcheckapp.seed

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide state machine for one incoming call ("session"):
 *
 *   RINGING  -> start session, ask the accessibility service to read the screen every second,
 *               schedule auto-reject at [REJECT_AFTER_MS]
 *   OFFHOOK / IDLE -> finish session, build the report, hand it to [SeedApi.submitReport]
 *
 * Fed by [CallStateWatcher] (call state + number) and [CallScreenAccessibilityService] (screen text).
 * All state changes happen on the main thread.
 */
object CallMonitor {
  private const val TAG = "SpamCheckMonitor"
  const val REJECT_AFTER_MS = 8_000L
  private const val CAPTURE_EVERY_MS = 1_000L
  private const val MAX_COLLECTED_TEXTS = 400
  const val MAX_REPORT_TEXTS = 40
  const val MAX_TEXT_LEN = 200

  private val main = Handler(Looper.getMainLooper())
  private var appContext: Context? = null
  private var watcher: CallStateWatcher? = null

  private class Session(val startMs: Long) {
    var number: String? = null
    val texts = LinkedHashSet<String>()
    var rejected = false
    var rejectError: String? = null
  }

  @Volatile private var current: Session? = null

  val isRinging: Boolean
    get() = current != null

  val isWatcherRegistered: Boolean
    get() = watcher?.isRegistered == true

  /** Registers the call-state listener once per process (safe to call repeatedly). */
  fun ensureWatching(ctx: Context) {
    val app = ctx.applicationContext
    appContext = app
    main.post {
      val w = watcher ?: CallStateWatcher(app).also { watcher = it }
      w.register() // no-op if already registered; retries if permission was missing before
    }
  }

  // ---- inputs -------------------------------------------------------------------------------

  fun onRinging(number: String?) =
      main.post {
        if (appContext == null) return@post
        var s = current
        if (s == null) {
          s = Session(System.currentTimeMillis())
          current = s
          Log.i(TAG, "Incoming call ringing, number=${number ?: "?"}")
          main.post(captureLoop)
          main.postDelayed(rejectRunnable, REJECT_AFTER_MS)
        }
        if (!number.isNullOrBlank() && s.number.isNullOrBlank()) s.number = number
      }

  fun onNumber(number: String?) {
    if (number.isNullOrBlank()) return
    main.post { current?.let { if (it.number.isNullOrBlank()) it.number = number } }
  }

  fun onCallEnded() =
      main.post {
        val ctx = appContext ?: return@post
        val s = current ?: return@post
        // last look at the screen before the session closes
        CallScreenAccessibilityService.instance?.captureNow()
        current = null
        main.removeCallbacks(captureLoop)
        main.removeCallbacks(rejectRunnable)
        finish(ctx, s, System.currentTimeMillis())
      }

  /** Called by the accessibility service with every text it read (any thread). */
  fun addTexts(texts: Collection<String>) {
    if (texts.isEmpty()) return
    main.post {
      val s = current ?: return@post
      for (raw in texts) {
        if (s.texts.size >= MAX_COLLECTED_TEXTS) break
        val t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) continue
        s.texts.add(if (t.length > MAX_TEXT_LEN) t.substring(0, MAX_TEXT_LEN) else t)
      }
    }
  }

  // ---- timers -------------------------------------------------------------------------------

  private val captureLoop: Runnable =
      object : Runnable {
        override fun run() {
          if (current == null) return
          CallScreenAccessibilityService.instance?.captureNow()
          main.postDelayed(this, CAPTURE_EVERY_MS)
        }
      }

  private val rejectRunnable = Runnable {
    val s = current ?: return@Runnable
    val ctx = appContext ?: return@Runnable
    CallScreenAccessibilityService.instance?.captureNow()
    val (ok, err) = endCall(ctx)
    s.rejected = ok
    s.rejectError = err
    Log.i(TAG, "Auto-reject after ${REJECT_AFTER_MS}ms: ok=$ok err=$err")
    // The IDLE callback normally follows and finishes the session. If the call could not be ended,
    // the session still finishes when the caller hangs up / it goes to voicemail.
  }

  @SuppressLint("MissingPermission")
  @Suppress("DEPRECATION")
  private fun endCall(ctx: Context): Pair<Boolean, String?> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
      return Pair(false, "endCall needs Android 9+")
    }
    if (ctx.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) !=
        PackageManager.PERMISSION_GRANTED) {
      return Pair(false, "ANSWER_PHONE_CALLS not granted")
    }
    return try {
      val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
      val ok = tm.endCall()
      Pair(ok, if (ok) null else "endCall returned false")
    } catch (e: Exception) {
      Log.w(TAG, "endCall failed", e)
      Pair(false, e.javaClass.simpleName + ": " + (e.message ?: ""))
    }
  }

  // ---- report -------------------------------------------------------------------------------

  private fun finish(ctx: Context, s: Session, endMs: Long) {
    val texts = ArrayList(s.texts)
    val number = s.number
    // Call log entries are written a moment after the call ends; look the number up off-thread.
    SeedApi.executor.execute {
      var finalNumber = number
      if (finalNumber.isNullOrBlank()) {
        try {
          Thread.sleep(1_500)
        } catch (e: InterruptedException) {}
        finalNumber = numberFromCallLog(ctx, s.startMs)
      }
      val payload = buildPayload(ctx, s, texts, finalNumber ?: "", endMs)
      Log.i(TAG, "Report: $payload")
      val local = JSONObject().put("reject_error", s.rejectError ?: JSONObject.NULL)
      SeedApi.submitReport(ctx, payload, local)
    }
  }

  private fun buildPayload(
      ctx: Context,
      s: Session,
      texts: List<String>,
      number: String,
      endMs: Long,
  ): JSONObject {
    val matched = SpamKeywords.match(texts)
    // keep texts that contain a keyword first, then the rest, capped
    val lowerKw = SpamKeywords.KEYWORDS
    val (hit, rest) = texts.partition { t -> lowerKw.any { t.lowercase(Locale.ROOT).contains(it) } }
    val capped = (hit + rest).take(MAX_REPORT_TEXTS)

    val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    iso.timeZone = TimeZone.getTimeZone("UTC")

    return JSONObject()
        .put("caller_number", number)
        .put("received_at", iso.format(Date(s.startMs)))
        .put("ring_seconds", Math.round((endMs - s.startMs) / 1000.0).toInt())
        .put("label_seen", matched.isNotEmpty())
        .put("matched_keywords", JSONArray(matched))
        .put("screen_texts", JSONArray(capped))
        .put("rejected", s.rejected)
        .put("app_operator", SeedApi.simOperator(ctx))
  }

  private fun numberFromCallLog(ctx: Context, sinceMs: Long): String? {
    if (ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) !=
        PackageManager.PERMISSION_GRANTED) return null
    return try {
      ctx.contentResolver
          .query(
              CallLog.Calls.CONTENT_URI,
              arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
              "${CallLog.Calls.DATE} >= ?",
              arrayOf((sinceMs - 60_000).toString()),
              "${CallLog.Calls.DATE} DESC",
          )
          ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
      Log.w(TAG, "call log lookup failed", e)
      null
    }
  }
}
