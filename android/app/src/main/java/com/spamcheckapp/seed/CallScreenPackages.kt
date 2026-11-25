package com.spamcheckapp.seed

import java.util.Locale

/**
 * Apps whose text can show a caller label: the dialer / in-call UI (Google, Samsung, Xiaomi, Oppo,
 * Vivo, OnePlus...), the telecom service and Truecaller-style caller-ID apps. Every other app on
 * screen is ignored, so a chat or mail that says "spam" is never read as a label.
 *
 * System UI is NOT read: it is the whole notification shade and status bar (mail subjects, other
 * apps' notifications). The heads-up incoming-call popup still arrives, as the dialer's own
 * notification event, under the dialer's package.
 */
object CallScreenPackages {
  private val MARKERS =
      listOf(
          "dialer",
          "incallui",
          "telecom",
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
