package com.spamcheckapp.seed

import java.util.Locale

/**
 * Apps whose text can show a caller label: the dialer / in-call UI (Google, Samsung, Xiaomi, Oppo,
 * Vivo, OnePlus...), the telecom service, Truecaller-style caller-ID apps, and System UI (the
 * heads-up incoming-call popup). Every other app on screen (WhatsApp, Gmail, ...) is ignored, so a
 * chat or mail that says "spam" is never read as a label.
 */
object CallScreenPackages {
  private val MARKERS =
      listOf(
          "dialer",
          "incallui",
          "telecom",
          "systemui",
          "truecaller",
          "callerid",
          "caller_id",
          "showcaller",
          "contacts",
          "com.android.phone",
      )

  fun isCallScreen(packageName: CharSequence?): Boolean {
    val pkg = packageName?.toString()?.lowercase(Locale.ROOT) ?: return false
    return MARKERS.any { pkg.contains(it) }
  }
}
