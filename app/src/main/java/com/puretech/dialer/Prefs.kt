package com.puretech.dialer

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

/** Central app settings (theme, assisted dialing, default SIM, quick responses). */
object Prefs {
    private const val FILE = "m5_dialer_prefs"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- Theme ---------------------------------------------------------------

    fun themeMode(c: Context) = sp(c).getInt("theme", THEME_SYSTEM)

    fun setThemeMode(c: Context, mode: Int) {
        sp(c).edit().putInt("theme", mode).apply()
        applyTheme(mode)
    }

    fun applyTheme(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    // --- Assisted dialing / home country ------------------------------------

    fun assistedDialing(c: Context) = sp(c).getBoolean("assisted", true)
    fun setAssistedDialing(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("assisted", on).apply()

    /** Stored home-country ISO, or null when set to auto-detect. */
    fun homeCountryOverride(c: Context): String? = sp(c).getString("home_country", null)
    fun setHomeCountry(c: Context, iso: String?) =
        sp(c).edit().putString("home_country", iso).apply()

    /** Effective home country: manual override, else SIM/network ISO, else device locale. */
    fun homeCountry(c: Context): String {
        homeCountryOverride(c)?.let { return it.uppercase(Locale.US) }
        return detectedCountry(c)
    }

    fun detectedCountry(c: Context): String {
        val tm = c.getSystemService(android.telephony.TelephonyManager::class.java)
        val iso = tm?.simCountryIso?.ifBlank { null }
            ?: tm?.networkCountryIso?.ifBlank { null }
            ?: Locale.getDefault().country
        return iso.uppercase(Locale.US)
    }

    // --- Default calling account (dual SIM) ---------------------------------

    fun defaultAccountId(c: Context): String? = sp(c).getString("default_account", null)
    fun setDefaultAccountId(c: Context, id: String?) =
        sp(c).edit().putString("default_account", id).apply()

    // --- UI state ------------------------------------------------------------

    /** Whether the call-log Favorites strip is expanded (persists across launches). */
    fun favoritesExpanded(c: Context) = sp(c).getBoolean("favorites_expanded", false)
    fun setFavoritesExpanded(c: Context, expanded: Boolean) =
        sp(c).edit().putBoolean("favorites_expanded", expanded).apply()

    /** Whether to show the optional floating on-screen-keypad button on the dial screen. */
    fun floatingDialButton(c: Context) = sp(c).getBoolean("floating_dial", false)
    fun setFloatingDialButton(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("floating_dial", on).apply()

    /** Open the on-screen keypad automatically every time the dial screen opens.
     *  It can still be minimized for the session and reopens on the next launch.
     *  For people who prefer to dial on-screen instead of with the hardware keys. */
    fun keypadDefaultOpen(c: Context) = sp(c).getBoolean("keypad_default_open", false)
    fun setKeypadDefaultOpen(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("keypad_default_open", on).apply()

    /** Make the on-screen keypad large (smartphone size) and add a green dial
     *  button at the bottom, so the app is usable on a normal touchscreen too. */
    fun bigKeypad(c: Context) = sp(c).getBoolean("big_keypad", false)
    fun setBigKeypad(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("big_keypad", on).apply()

    /** Answer/decline incoming calls by sliding a button sideways (Google-style),
     *  instead of tapping the round Answer/Decline buttons. Off by default. */
    fun swipeToAnswer(c: Context) = sp(c).getBoolean("swipe_to_answer", false)
    fun setSwipeToAnswer(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("swipe_to_answer", on).apply()

    /** Experimental: block outgoing calls to known AI phone-call services
     *  (see [AiBlocklist]), even from a contact. Off by default. */
    fun blockAiNumbers(c: Context) = sp(c).getBoolean("block_ai_numbers", false)
    fun setBlockAiNumbers(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("block_ai_numbers", on).apply()

    // --- Automatic update checks --------------------------------------------

    const val UPDATE_MANUAL = 0
    const val UPDATE_DAILY = 1
    const val UPDATE_WEEKLY = 2
    const val UPDATE_MONTHLY = 3

    /** How often to check GitHub for a new release in the background.
     *  Defaults to a once-a-day check. */
    fun updateFrequency(c: Context) = sp(c).getInt("update_frequency", UPDATE_DAILY)
    fun setUpdateFrequency(c: Context, mode: Int) =
        sp(c).edit().putInt("update_frequency", mode).apply()

    /** Tag of the release we last raised a notification for, so a background
     *  check doesn't notify about the same version more than once. */
    fun lastNotifiedTag(c: Context): String? = sp(c).getString("last_notified_tag", null)
    fun setLastNotifiedTag(c: Context, tag: String) =
        sp(c).edit().putString("last_notified_tag", tag).apply()

    /** Play a DTMF tone when pressing a key on the dial screen. On by default.
     *  (In-call keypad tones always play, regardless of this setting.) */
    fun dialpadTone(c: Context) = sp(c).getBoolean("dialpad_tone", true)
    fun setDialpadTone(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("dialpad_tone", on).apply()

    /** Keep a foreground service running so incoming calls always surface on
     *  ROMs that freeze background apps (e.g. DuraSpeed on the F21). Off by
     *  default because it shows a permanent low-priority notification. */
    fun keepAlive(c: Context) = sp(c).getBoolean("keep_alive", false)
    fun setKeepAlive(c: Context, on: Boolean) =
        sp(c).edit().putBoolean("keep_alive", on).apply()

    /** Whether the one-time welcome/onboarding screen has been shown. */
    fun welcomeShown(c: Context) = sp(c).getBoolean("welcome_shown", false)
    fun setWelcomeShown(c: Context) = sp(c).edit().putBoolean("welcome_shown", true).apply()

    // --- Quick responses -----------------------------------------------------

    fun quickResponses(c: Context): List<String> {
        val def = c.resources.getStringArray(R.array.quick_responses)
        return (0 until def.size).map {
            sp(c).getString("qr_$it", null) ?: def[it]
        }
    }

    fun setQuickResponse(c: Context, index: Int, text: String) =
        sp(c).edit().putString("qr_$index", text).apply()
}
