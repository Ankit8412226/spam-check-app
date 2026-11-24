# Spam Check Seed (Android)

A small React Native (bare, Android only) app for the **seed phone**: one of our own Android phones
with one SIM (Jio / Airtel / Vi / BSNL). The Qualia-Voice backend calls the seed phone from a dialer
line. When the call rings, the app:

1. detects the ringing call and the caller number,
2. reads the text on the incoming-call screen (system dialer / Google Phone UI, the heads-up call
   notification, the Truecaller overlay, operator labels such as Airtel's "Suspected Spam") through
   an Android **AccessibilityService**,
3. looks for spam keywords in that text,
4. rejects the call after **8 seconds** of ringing,
5. POSTs a report to the backend (and retries if that fails),
6. sends a heartbeat every 3 hours, on app open and after each report, so the admin can see the
   phone is alive.

Detection and reporting run in native Kotlin, so they work while the app is in the background.
The React Native screen is only for settings, status and the log.

> **The seed phone rejects and reports EVERY incoming call.** Use a phone and SIM that only receive
> our test calls.

---

## Build (Android Studio)

Requirements: Android Studio (recent), **JDK 17** (Android Studio's bundled JBR is fine), Android SDK
with platform **37** and build-tools **37.0.0** (Android Studio offers to install missing ones), and
Node.js **22.11+**.

```bash
cd spam-check-app
npm install            # only if node_modules is missing
```

1. Android Studio -> **Open** -> choose the `spam-check-app/android` folder (not the project root).
2. Wait for Gradle sync. If asked, set *Settings -> Build Tools -> Gradle -> Gradle JDK* to JDK 17.
3. Build a stand-alone APK (the JS bundle is embedded, no Metro server needed):
   - *Build -> Select Build Variant -> app: release*, then **Build -> Build APK(s)**, or from a
     terminal:
     ```bash
     cd android && ./gradlew assembleRelease
     ```
   - Output: `android/app/build/outputs/apk/release/app-release.apk`.
   - The release build is signed with the template's debug keystore. That is fine for our own
     phones; create a real keystore before any wider distribution.
4. A debug APK (`./gradlew assembleDebug`) needs a running Metro server (`npm start`) and a USB /
   Wi-Fi connection, so use **release** for the seed phone.

## Install

```bash
adb install -r android/app/build/outputs/apk/release/app-release.apk
```
or copy the APK to the phone and open it (allow "Install unknown apps" for the file manager).

## Set up the phone (do all of these)

Open **Spam Check Seed**, then:

1. **Grant permissions** button -> allow *Phone* (READ_PHONE_STATE + ANSWER_PHONE_CALLS),
   *Call logs* (READ_CALL_LOG) and *Notifications*.
   - On Android 13+ a sideloaded app may show "Restricted setting" for call log / accessibility.
     Fix: *Settings -> Apps -> Spam Check Seed -> three-dot menu -> Allow restricted settings*,
     then grant again.
2. **Open Accessibility settings** -> *Installed / Downloaded apps* -> *Spam Check Seed - call
   screen reader* -> turn **on**. Status must show "Accessibility service enabled: YES" and
   "connected: YES".
3. **Battery optimisation settings** -> Spam Check Seed -> **Don't optimise / Unrestricted**.
   On Xiaomi / Vivo / Oppo / Realme / Samsung also enable **Autostart** and lock the app in the
   recents screen; otherwise the OS may kill the service.
4. Being the **default dialer is NOT required**. Do not change the default phone app.
5. Leave Truecaller (if installed) and the operator's caller-ID features as they are; the point is
   to see what a normal customer sees.
6. The notification "Seed phone monitor running" must stay visible (that is the foreground service).
   Status should show "Foreground service running: YES" and "Call listener registered: YES".

## Enter the server details

In the **Settings** section:

- **Server base URL**: pre-filled with production, `https://dialerserver.qualiabits.com` (the
  Qualia-Voice dialer server, without `/api`). Change it only to point at another server.
- **Seed token**: the token the admin panel shows **once** when the seed is created
  (sent as header `X-Seed-Token`).
- Tap **Save**, then **Send test heartbeat**. "Heartbeat OK" with the seed label means the phone is
  registered. HTTP 401/403 means a wrong token.

`http://` URLs are allowed (cleartext traffic is enabled) but prefer `https://`.

## What is sent

`POST {base}/api/spam-check/seed/heartbeat`
```json
{ "app_operator": "Airtel", "app_version": "1.0", "android_version": "14 (API 34)" }
```

`POST {base}/api/spam-check/seed/report`
```json
{
  "caller_number": "+917965110243",
  "received_at": "2026-11-24T10:15:02.123Z",
  "ring_seconds": 8,
  "label_seen": true,
  "matched_keywords": ["spam", "suspected"],
  "screen_texts": ["Suspected Spam", "+91 79651 10243", "..."],
  "rejected": true,
  "app_operator": "Airtel"
}
```
- `screen_texts`: de-duplicated, at most 40 strings x 200 chars; texts containing a keyword come
  first.
- `app_operator`: `TelephonyManager.getSimOperatorName()` (falls back to the network operator name).
- `caller_number`: from the phone-state broadcast / listener; if empty, from the newest call-log
  entry after the call ends. It is in whatever format the phone gives (`+91...`, `0...` or 10
  digits) - the backend should normalise.
- `ring_seconds`: whole seconds from ringing start to the call ending.
- Failed sends are retried 3 times (after 2 s, 5 s, 15 s), and again after each later successful
  heartbeat (for up to 24 h). The last 50 events are kept on the phone and shown in the **Log**.

## Spam keywords

Defined in one place:
`android/app/src/main/java/com/spamcheckapp/seed/SpamKeywords.kt`

```
spam, suspected, fraud, scam, telemarketer, likely spam, spam risk, and Hindi: स्पैम, संदिग्ध, धोखाधड़ी, फ्रॉड

Texts that mention a keyword without being a label ("Not spam", "Report spam", "Block & report spam",
"Caller ID & spam", "Spam filter" …) are removed before matching: see `NOT_A_LABEL` in the same file.
```
Matching is case-insensitive substring matching on every text read from the screen. To change the
list, edit that file and rebuild the APK.

## Must be checked on real phones (first week)

The app has not been run on a device yet. On each seed phone please verify:

- **How each label is actually rendered**: Airtel's "Suspected Spam", Jio / Vi / BSNL labels (if
  any), Truecaller's overlay, Google Phone's "Suspected spam caller", Samsung / Xiaomi dialers.
  Some labels may be drawn as images, appear only in notification text, or sit in a window the
  accessibility API does not expose. Check the **Screen:** line in the Log for each test call and
  adjust `SpamKeywords.kt` (including any Hindi / regional wording that shows up).
- "warning" alone is not a keyword (too common); "Airtel Warning: Suspected Spam" still matches on "spam".
- **Locked vs unlocked screen**: unlocked phones often show a heads-up notification instead of the
  full-screen call UI. Test both.
- **Auto-reject**: `TelecomManager.endCall()` works on Android 9+ with ANSWER_PHONE_CALLS; some OEM
  ROMs may ignore it. If the Log shows "rejected: no (...)", the call rings out and is still
  reported.
- **Caller number**: confirm it is filled in on each Android version (needs both Phone and Call
  log permissions).
- **Background survival**: after a reboot and after a night idle, confirm the notification is back,
  heartbeats keep arriving in the admin panel, and a test call is still reported. Heartbeats use a
  timer inside the foreground service (WorkManager is not in the template), so during deep Doze
  they may arrive late.

## Code layout

```
App.tsx                        one-screen UI: Settings / Status / Log
src/SpamCheck.ts               typed wrapper around NativeModules.SpamCheck + permission requests
android/app/src/main/
  AndroidManifest.xml          permissions, services, boot receiver
  res/xml/accessibility_service_config.xml
  java/com/spamcheckapp/
    MainApplication.kt         registers SpamCheckPackage, starts the call listener
    seed/SpamKeywords.kt       keyword list + matcher
    seed/CallStateWatcher.kt   TelephonyCallback (API 31+) / PhoneStateListener + PHONE_STATE receiver
    seed/CallMonitor.kt        per-call session: collect texts, reject at 8 s, build report
    seed/CallScreenAccessibilityService.kt  reads screen text while ringing
    seed/SeedMonitorService.kt foreground service (specialUse) + 3-hourly heartbeat
    seed/SeedApi.kt            HttpURLConnection client, retries, heartbeat, report queue
    seed/SeedPrefs.kt          SharedPreferences: URL, token, last 50 events, last heartbeat
    seed/BootReceiver.kt       restarts the service after reboot / app update
    seed/SpamCheckModule.kt    React Native bridge (config, status, events, settings shortcuts)
    seed/SpamCheckPackage.kt   ReactPackage for the module
```

Logs: `adb logcat -s SpamCheckMonitor SpamCheckA11y SpamCheckWatcher SpamCheckApi SpamCheckFgs`
