package com.puretech.dialer

import android.content.Context

/**
 * Reformats an already-resolved contact/caller display name per the "Name
 * format" setting (Settings > Display). Only ever applied at the point a
 * name is about to be shown — never before persisting (LocalCallStore,
 * Quick dial assignments) and never before name-matching (T9 search keys are
 * computed from the original name first) — so flipping the setting later
 * reformats everything already saved without any data migration.
 *
 * Heuristic: the system only ever hands us a single flat display-name string
 * (no separate given/family name fields on the call-log/notification paths),
 * so "Last name First" is done by splitting on the LAST space — everything
 * before it is the given name(s), the final word is the family name. Works
 * for the common "First [Middle] Last" Western pattern; a single-word name
 * (or one with no space) is left unchanged either way.
 */
object NameFormat {
    fun apply(context: Context, name: String?): String? {
        if (name.isNullOrBlank()) return name
        if (!Prefs.lastNameFirst(context)) return name
        val trimmed = name.trim()
        val lastSpace = trimmed.lastIndexOf(' ')
        if (lastSpace <= 0) return trimmed
        val given = trimmed.substring(0, lastSpace).trim()
        val family = trimmed.substring(lastSpace + 1).trim()
        if (given.isEmpty() || family.isEmpty()) return trimmed
        return "$family, $given"
    }
}
