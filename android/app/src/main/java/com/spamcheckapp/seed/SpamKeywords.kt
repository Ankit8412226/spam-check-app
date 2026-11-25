package com.spamcheckapp.seed

import java.util.Locale

/**
 * The ONE place where spam keywords live. Matching is case-insensitive substring matching
 * against every text string read from the incoming-call screen.
 *
 * Button and menu texts that contain a keyword but are not a label ("Not spam", "Report spam",
 * "Block & report spam") are removed from a text before matching, so they never count as a label.
 *
 * Adjust after checking how each operator / Truecaller / Google Phone renders labels on real phones.
 */
object SpamKeywords {
  val KEYWORDS: List<String> =
      listOf(
          "spam",
          "suspected",
          "fraud",
          "scam",
          "telemarketer",
          "likely spam",
          "spam risk",
          // Hindi (phone / Truecaller set to Hindi)
          "स्पैम",
          "संदिग्ध",
          "धोखाधड़ी",
          "फ्रॉड",
      )

  /** Phrases that mention a keyword without being a label (buttons, menus, settings). */
  val NOT_A_LABEL: List<String> =
      listOf(
          "not spam",
          "not a spam",
          "report spam",
          "report as spam",
          "mark as spam",
          "block & report spam",
          "block and report spam",
          "block/report spam",
          "report fraud",
          "spam protection",
          "spam filter",
          "caller id & spam",
          "caller id and spam",
          "filter spam calls",
          // This app's own name, shown in the status bar / notification shade (System UI).
          "spam check",
      )

  /** Returns the keywords (from [KEYWORDS]) found in any of [texts], in list order, no duplicates. */
  fun match(texts: Collection<String>): List<String> {
    val cleaned =
        texts.map { text ->
          NOT_A_LABEL.fold(text.lowercase(Locale.ROOT)) { acc, phrase -> acc.replace(phrase, " ") }
        }
    return KEYWORDS.filter { kw -> cleaned.any { it.contains(kw) } }
  }
}
