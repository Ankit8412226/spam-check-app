package com.spamcheckapp.seed

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences store shared by the RN UI (through [SpamCheckModule]) and the background
 * services: server config, the last [MAX_EVENTS] call events and the last heartbeat result.
 */
object SeedPrefs {
  private const val FILE = "spam_check_seed"
  private const val KEY_BASE_URL = "base_url"
  private const val KEY_TOKEN = "seed_token"
  private const val KEY_EVENTS = "events"
  private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
  const val MAX_EVENTS = 50

  private val lock = Any()

  private fun prefs(ctx: Context): SharedPreferences =
      ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  fun baseUrl(ctx: Context): String = prefs(ctx).getString(KEY_BASE_URL, "") ?: ""

  fun token(ctx: Context): String = prefs(ctx).getString(KEY_TOKEN, "") ?: ""

  fun isConfigured(ctx: Context): Boolean = baseUrl(ctx).isNotBlank() && token(ctx).isNotBlank()

  fun saveConfig(ctx: Context, baseUrl: String, token: String) {
    prefs(ctx)
        .edit()
        .putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
        .putString(KEY_TOKEN, token.trim())
        .apply()
  }

  // ---- events -------------------------------------------------------------------------------

  /** Events, newest first. Each: {id, payload:{...report body...}, sent, attempts, last_error, http_status, matched_result_id}. */
  fun events(ctx: Context): JSONArray =
      synchronized(lock) {
        try {
          JSONArray(prefs(ctx).getString(KEY_EVENTS, "[]"))
        } catch (e: Exception) {
          JSONArray()
        }
      }

  fun eventsJson(ctx: Context): String = events(ctx).toString()

  fun clearEvents(ctx: Context) =
      synchronized(lock) { prefs(ctx).edit().putString(KEY_EVENTS, "[]").apply() }

  /** Inserts or replaces (by "id") an event and trims the list to [MAX_EVENTS]. */
  fun upsertEvent(ctx: Context, event: JSONObject) =
      synchronized(lock) {
        val old = events(ctx)
        val out = JSONArray()
        out.put(event)
        val id = event.optString("id")
        for (i in 0 until old.length()) {
          if (out.length() >= MAX_EVENTS) break
          val e = old.optJSONObject(i) ?: continue
          if (e.optString("id") == id) continue
          out.put(e)
        }
        prefs(ctx).edit().putString(KEY_EVENTS, out.toString()).apply()
      }

  /** Updates an existing event in place (keeps its position). */
  fun updateEvent(ctx: Context, event: JSONObject) =
      synchronized(lock) {
        val arr = events(ctx)
        val id = event.optString("id")
        for (i in 0 until arr.length()) {
          if (arr.optJSONObject(i)?.optString("id") == id) {
            arr.put(i, event)
            prefs(ctx).edit().putString(KEY_EVENTS, arr.toString()).apply()
            return@synchronized
          }
        }
      }

  // ---- heartbeat ----------------------------------------------------------------------------

  fun saveLastHeartbeat(ctx: Context, result: JSONObject) =
      prefs(ctx).edit().putString(KEY_LAST_HEARTBEAT, result.toString()).apply()

  fun lastHeartbeatJson(ctx: Context): String =
      prefs(ctx).getString(KEY_LAST_HEARTBEAT, "") ?: ""
}
