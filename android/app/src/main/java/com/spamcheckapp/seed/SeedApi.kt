package com.spamcheckapp.seed

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plain HttpURLConnection client for the Qualia-Voice spam-check seed API.
 * All network work runs on [executor]; never call [post] from the main thread.
 */
object SeedApi {
  private const val TAG = "SpamCheckApi"
  private const val APP_VERSION = "1.0"
  private val RETRY_DELAYS_MS = longArrayOf(2_000, 5_000, 15_000)

  /** Single background thread: reports and heartbeats are sent one at a time, in order. */
  val executor = Executors.newSingleThreadExecutor()

  data class Result(val ok: Boolean, val httpStatus: Int, val body: String, val error: String?) {
    fun json(): JSONObject? =
        try {
          JSONObject(body)
        } catch (e: Exception) {
          null
        }
  }

  fun simOperator(ctx: Context): String {
    return try {
      val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      val sim = tm.simOperatorName ?: ""
      if (sim.isNotBlank()) sim else (tm.networkOperatorName ?: "")
    } catch (e: Exception) {
      ""
    }
  }

  /** One POST attempt. */
  fun post(ctx: Context, path: String, body: JSONObject): Result {
    val base = SeedPrefs.baseUrl(ctx)
    val token = SeedPrefs.token(ctx)
    if (base.isBlank() || token.isBlank()) {
      return Result(false, 0, "", "Base URL / seed token not configured")
    }
    var conn: HttpURLConnection? = null
    return try {
      conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15_000
        readTimeout = 20_000
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=utf-8")
        setRequestProperty("Accept", "application/json")
        setRequestProperty("X-Seed-Token", token)
      }
      conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      val status = conn.responseCode
      val stream = if (status in 200..299) conn.inputStream else conn.errorStream
      val text =
          stream?.let { s -> BufferedReader(InputStreamReader(s, Charsets.UTF_8)).use { it.readText() } }
              ?: ""
      val ok = status in 200..299
      Result(ok, status, text, if (ok) null else "HTTP $status")
    } catch (e: Exception) {
      Log.w(TAG, "POST $path failed", e)
      Result(false, 0, "", e.javaClass.simpleName + ": " + (e.message ?: ""))
    } finally {
      conn?.disconnect()
    }
  }

  /** POST with retries. 4xx (except 408/429) is not retried: the request itself is wrong. */
  fun postWithRetry(ctx: Context, path: String, body: JSONObject): Pair<Result, Int> {
    var attempt = 0
    var res = post(ctx, path, body)
    attempt++
    while (!res.ok && attempt <= RETRY_DELAYS_MS.size && isRetryable(res)) {
      try {
        Thread.sleep(RETRY_DELAYS_MS[attempt - 1])
      } catch (e: InterruptedException) {
        break
      }
      res = post(ctx, path, body)
      attempt++
    }
    return Pair(res, attempt)
  }

  private fun isRetryable(res: Result): Boolean {
    if (res.error?.startsWith("Base URL") == true) return false
    val st = res.httpStatus
    return st == 0 || st >= 500 || st == 408 || st == 429
  }

  // ---- heartbeat ----------------------------------------------------------------------------

  /** Blocking heartbeat (call on [executor] or another background thread). */
  fun heartbeatBlocking(ctx: Context): Result {
    val body = JSONObject()
        .put("app_operator", simOperator(ctx))
        .put("app_version", APP_VERSION)
        .put("android_version", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
    val (res, _) = postWithRetry(ctx, "/api/spam-check/seed/heartbeat", body)
    val record = JSONObject()
        .put("at", System.currentTimeMillis())
        .put("ok", res.ok)
        .put("http_status", res.httpStatus)
        .put("error", res.error ?: JSONObject.NULL)
    res.json()?.let { j ->
      record.put("seed_id", j.opt("seed_id") ?: JSONObject.NULL)
      record.put("label", j.opt("label") ?: JSONObject.NULL)
    }
    SeedPrefs.saveLastHeartbeat(ctx, record)
    if (res.ok) retryUnsentBlocking(ctx)
    return res
  }

  fun heartbeatAsync(ctx: Context) {
    val app = ctx.applicationContext
    if (!SeedPrefs.isConfigured(app)) return
    executor.execute { heartbeatBlocking(app) }
  }

  // ---- reports ------------------------------------------------------------------------------

  /** Stores the event (sent=false) immediately, then sends it in the background, then heartbeats. */
  fun submitReport(ctx: Context, payload: JSONObject, local: JSONObject? = null) {
    val app = ctx.applicationContext
    val event = JSONObject()
        .put("id", payload.optString("received_at") + "_" + System.nanoTime())
        .put("created_ms", System.currentTimeMillis())
        .put("payload", payload)
        .put("sent", false)
        .put("attempts", 0)
        .put("last_error", JSONObject.NULL)
        .put("local", local ?: JSONObject())
    SeedPrefs.upsertEvent(app, event)
    executor.execute {
      sendEventBlocking(app, event)
      heartbeatBlocking(app)
    }
  }

  private fun sendEventBlocking(ctx: Context, event: JSONObject) {
    val payload = event.optJSONObject("payload") ?: return
    val (res, attempts) = postWithRetry(ctx, "/api/spam-check/seed/report", payload)
    event.put("sent", res.ok)
    event.put("attempts", event.optInt("attempts") + attempts)
    event.put("http_status", res.httpStatus)
    event.put("last_error", res.error ?: JSONObject.NULL)
    res.json()?.opt("matched_result_id")?.let { event.put("matched_result_id", it) }
    SeedPrefs.updateEvent(ctx, event)
  }

  /** Re-sends up to 10 unsent events younger than 24h (called after a successful heartbeat). */
  private fun retryUnsentBlocking(ctx: Context) {
    val events: JSONArray = SeedPrefs.events(ctx)
    val cutoff = System.currentTimeMillis() - 24L * 3600 * 1000
    var sent = 0
    for (i in 0 until events.length()) {
      if (sent >= 10) break
      val e = events.optJSONObject(i) ?: continue
      if (e.optBoolean("sent")) continue
      if (e.optInt("attempts") >= 12) continue
      if (e.optLong("created_ms", System.currentTimeMillis()) < cutoff) continue
      sendEventBlocking(ctx, e)
      sent++
    }
  }
}
