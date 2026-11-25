package com.spamcheckapp.seed

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Reads the text on screen while a call is ringing: the system dialer / Google Phone incoming-call
 * UI, the heads-up call notification, Truecaller's caller-ID overlay and any operator label.
 *
 * It only collects text while [CallMonitor.isRinging] is true, and only from call-screen apps
 * ([CallScreenPackages]); other apps on screen are ignored.
 * It also keeps the process alive (system-bound), so it registers the call-state listener and
 * tries to start [SeedMonitorService].
 */
class CallScreenAccessibilityService : AccessibilityService() {

  private var lastCaptureMs = 0L

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    Log.i(TAG, "accessibility service connected")
    CallMonitor.ensureWatching(this)
    SeedMonitorService.start(this)
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null || !CallMonitor.isRinging) return
    if (event.packageName?.toString() == packageName) return
    val callScreen = CallScreenPackages.isCallScreen(event.packageName)
    CallMonitor.noteApp(event.packageName, callScreen)
    if (!callScreen) return // WhatsApp, Gmail, launcher ... never count
    // Event text covers the heads-up / notification case, even when the window tree is not exposed.
    val texts = ArrayList<String>()
    event.text?.forEach { cs -> cs?.toString()?.let { texts.add(it) } }
    event.contentDescription?.toString()?.let { texts.add(it) }
    CallMonitor.addTexts(texts)

    val now = SystemClock.elapsedRealtime()
    if (now - lastCaptureMs >= 300) captureNow()
  }

  /** Walks every window currently on screen and forwards their texts. Main thread. */
  fun captureNow() {
    lastCaptureMs = SystemClock.elapsedRealtime()
    val texts = ArrayList<String>()
    val roots = ArrayList<AccessibilityNodeInfo>()
    try {
      windows?.forEach { w -> w?.root?.let { roots.add(it) } }
    } catch (e: Exception) {
      Log.w(TAG, "getWindows failed", e)
    }
    if (roots.isEmpty()) {
      try {
        rootInActiveWindow?.let { roots.add(it) }
      } catch (e: Exception) {
        Log.w(TAG, "rootInActiveWindow failed", e)
      }
    }
    for (root in roots) collect(root, texts)
    CallMonitor.addTexts(texts)
  }

  private fun collect(root: AccessibilityNodeInfo, out: MutableList<String>) {
    if (root.packageName?.toString() == packageName) return // never read our own UI
    val callScreen = CallScreenPackages.isCallScreen(root.packageName)
    CallMonitor.noteApp(root.packageName, callScreen)
    if (!callScreen) return // only call-screen apps
    val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
    queue.add(Pair(root, 0))
    var visited = 0
    while (queue.isNotEmpty() && visited < MAX_NODES) {
      val (node, depth) = queue.removeFirst()
      visited++
      node.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
      node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
      if (depth >= MAX_DEPTH) continue
      for (i in 0 until node.childCount) {
        val child =
            try {
              node.getChild(i)
            } catch (e: Exception) {
              null
            } ?: continue
        queue.add(Pair(child, depth + 1))
      }
    }
  }

  override fun onInterrupt() {}

  override fun onDestroy() {
    if (instance === this) instance = null
    super.onDestroy()
  }

  companion object {
    private const val TAG = "SpamCheckA11y"
    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 40

    @Volatile var instance: CallScreenAccessibilityService? = null
      private set
  }
}
