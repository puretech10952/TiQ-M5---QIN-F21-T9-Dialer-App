package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.puretech.dialer.databinding.ActivityHomeBinding
import com.puretech.dialer.vvm.VvmPrefs

/**
 * Single host that keeps ONE persistent bottom bar (Home | Keypad | Voicemail)
 * and swaps the content above it between [RecentsFragment], [DialerFragment],
 * and [VoicemailFragment]. The fragments are added once and shown/hidden (never
 * recreated), so the bar never blinks and each tab keeps its state. The drawer
 * and the on-screen keypad live here too.
 */
class HomeActivity : AppCompatActivity() {

    private enum class Tab { RECENTS, DIALER, VOICEMAIL }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recentsFragment: RecentsFragment
    private lateinit var dialerFragment: DialerFragment
    private lateinit var voicemailFragment: VoicemailFragment
    private var currentTab = Tab.RECENTS
    private var didAutoOpenKeypad = false
    private var barHiderAttached = false
    private var suppressNav = false

    private val barHider by lazy { BottomBarHider(binding.bottomNav) }

    private val dialKeys by lazy {
        listOf(
            binding.key1, binding.key2, binding.key3, binding.key4, binding.key5,
            binding.key6, binding.key7, binding.key8, binding.key9,
            binding.keyStar, binding.key0, binding.keyHash
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Add all three fragments once (synchronously so their views exist below).
        if (savedInstanceState == null) {
            recentsFragment = RecentsFragment()
            dialerFragment = DialerFragment()
            voicemailFragment = VoicemailFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, recentsFragment, TAG_RECENTS)
                .add(R.id.fragmentContainer, dialerFragment, TAG_DIALER)
                .add(R.id.fragmentContainer, voicemailFragment, TAG_VOICEMAIL)
                .hide(dialerFragment)
                .hide(voicemailFragment)
                .commitNow()
        } else {
            recentsFragment = supportFragmentManager.findFragmentByTag(TAG_RECENTS) as RecentsFragment
            dialerFragment = supportFragmentManager.findFragmentByTag(TAG_DIALER) as DialerFragment
            voicemailFragment = supportFragmentManager.findFragmentByTag(TAG_VOICEMAIL) as VoicemailFragment
            currentTab = when {
                !dialerFragment.isHidden -> Tab.DIALER
                !voicemailFragment.isHidden -> Tab.VOICEMAIL
                else -> Tab.RECENTS
            }
        }

        setupDrawer()
        setupBottomBar()
        setupKeypad()
        setupBack()

        binding.drawerLayout.setDrawerLockMode(
            if (currentTab == Tab.DIALER) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else DrawerLayout.LOCK_MODE_UNLOCKED
        )
        routeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Gates.enforce(this)) return
        WhatsNewSheet.maybeShowIfUpdated(this)
        ensureBarHider()
        refreshBarHiderForProfile()
        updateVoicemailNavVisibility()
        when (currentTab) {
            Tab.DIALER -> {
                applyKeypadSetting()
                dialerFragment.onTabResumed()
            }
            Tab.VOICEMAIL -> voicemailFragment.onTabResumed()
            Tab.RECENTS -> recentsFragment.onTabResumed()
        }
    }

    /** The Voicemail tab on the bottom bar shows automatically once the user
     *  has turned on visual voicemail -- no separate switch for it. Called on
     *  every resume so enabling/disabling visual voicemail in Settings and
     *  coming back updates the bar immediately. If the tab being hidden was
     *  the one currently open, fall back to Home rather than leaving the bar
     *  with no selected item pointing at a hidden tab. */
    private fun updateVoicemailNavVisibility() {
        val show = VvmPrefs.enabled(this)
        binding.bottomNav.menu.findItem(R.id.tab_voicemail)?.isVisible = show
        if (!show && currentTab == Tab.VOICEMAIL) showTab(Tab.RECENTS)
    }

    /** Reflects a screen-profile change made elsewhere (Settings) the moment
     *  this activity resumes, regardless of which tab is showing. */
    private fun refreshBarHiderForProfile() {
        if (Prefs.screenProfile(this) == Prefs.PROFILE_LARGE) {
            barHider.enabled = false
            barHider.show()
        } else if (binding.dialpadPanel.visibility != View.VISIBLE) {
            barHider.enabled = true
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        dialerFragment.onLeaveHint()
    }

    override fun onStop() {
        super.onStop()
        dialerFragment.onHostStopped()
    }

    // --- Setup -----------------------------------------------------------------

    private fun setupDrawer() {
        binding.drawerVersion.text = getString(R.string.drawer_version, appVersionName())
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_search -> startActivity(Intent(this, CallLogSearchActivity::class.java))
                R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
                R.id.nav_durations -> startActivity(Intent(this, CallStatsActivity::class.java))
                R.id.nav_delete_all_logs -> confirmDeleteAllLogs()
                R.id.nav_updates -> startActivity(Intent(this, UpdateActivity::class.java))
                R.id.nav_feedback -> Feedback.send(this)
                R.id.nav_about -> startActivity(Intent(this, AboutActivity::class.java))
            }
            binding.drawerLayout.closeDrawers()
            true
        }
    }

    fun openDrawer() = binding.drawerLayout.openDrawer(GravityCompat.START)

    private fun confirmDeleteAllLogs() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.delete_all_logs_confirm)
            .setPositiveButton(R.string.log_delete) { _, _ -> deleteAllLogs() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteAllLogs() {
        val ctx = applicationContext
        Thread {
            CallLogRepository.deleteAll(ctx)
            runOnUiThread {
                Toast.makeText(this, R.string.all_logs_deleted, Toast.LENGTH_SHORT).show()
                recentsFragment.reload()
            }
        }.start()
    }

    private fun setupBottomBar() {
        // Set the initial selection before wiring the listener so it doesn't fire.
        binding.bottomNav.selectedItemId = navItemFor(currentTab)
        updateVoicemailNavVisibility()
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (!suppressNav) when (item.itemId) {
                R.id.tab_home -> showTab(Tab.RECENTS)
                R.id.tab_keypad -> showTab(Tab.DIALER)
                R.id.tab_voicemail -> showTab(Tab.VOICEMAIL)
            }
            true
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> recentsFragment.scrollToTopAndClearSearch()
                R.id.tab_keypad -> setKeypadShown(binding.dialpadPanel.visibility != View.VISIBLE)
                R.id.tab_voicemail -> voicemailFragment.onTabResumed()
            }
        }
    }

    private fun navItemFor(tab: Tab): Int = when (tab) {
        Tab.DIALER -> R.id.tab_keypad
        Tab.VOICEMAIL -> R.id.tab_voicemail
        Tab.RECENTS -> R.id.tab_home
    }

    private fun setupKeypad() {
        val keys = mapOf(
            binding.key0 to '0', binding.key1 to '1', binding.key2 to '2', binding.key3 to '3',
            binding.key4 to '4', binding.key5 to '5', binding.key6 to '6', binding.key7 to '7',
            binding.key8 to '8', binding.key9 to '9',
            binding.keyStar to '*', binding.keyHash to '#'
        )
        for ((view, ch) in keys) view.setOnClickListener { dialerFragment.insert(ch) }
        binding.key0.setOnLongClickListener { dialerFragment.insert('+'); true }
        binding.btnMinimize.setOnClickListener { setKeypadShown(false) }
        binding.btnDialBig.setOnClickListener { dialerFragment.startCall() }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    binding.drawerLayout.closeDrawers()
                currentTab == Tab.DIALER && dialerFragment.hasText() -> dialerFragment.backspace()
                currentTab == Tab.DIALER -> showTab(Tab.RECENTS)
                currentTab == Tab.VOICEMAIL && voicemailFragment.isSelecting() -> voicemailFragment.exitSelection()
                currentTab == Tab.RECENTS && recentsFragment.handleBack() -> { /* consumed */ }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    /** Attach the single scroll-hider to all three lists once their views exist.
     *  Only the visible tab's list scrolls, so one hider on all is safe. */
    private fun ensureBarHider() {
        if (barHiderAttached) return
        val r = recentsFragment.scrollTarget() ?: return
        val d = dialerFragment.scrollTarget() ?: return
        val v = voicemailFragment.scrollTarget() ?: return
        r.addOnScrollListener(barHider)
        d.addOnScrollListener(barHider)
        v.addOnScrollListener(barHider)
        barHiderAttached = true
    }

    // --- Tab switching ---------------------------------------------------------

    private fun showTab(tab: Tab) {
        if (tab == currentTab) {
            if (tab == Tab.RECENTS) recentsFragment.scrollToTopAndClearSearch()
            return
        }
        val fragmentFor = mapOf(
            Tab.RECENTS to recentsFragment, Tab.DIALER to dialerFragment, Tab.VOICEMAIL to voicemailFragment
        )
        supportFragmentManager.beginTransaction().apply {
            show(fragmentFor.getValue(tab))
            for ((t, f) in fragmentFor) if (t != tab) hide(f)
        }.commit()
        currentTab = tab
        suppressNav = true
        binding.bottomNav.selectedItemId = navItemFor(tab)
        suppressNav = false
        binding.drawerLayout.setDrawerLockMode(
            if (tab == Tab.DIALER) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else DrawerLayout.LOCK_MODE_UNLOCKED
        )
        barHider.show()
        when (tab) {
            Tab.DIALER -> {
                if (Prefs.screenProfile(this) == Prefs.PROFILE_LARGE) didAutoOpenKeypad = false
                applyKeypadSetting()
                dialerFragment.onTabResumed()
            }
            Tab.VOICEMAIL -> {
                setKeypadShown(false)
                voicemailFragment.onTabResumed()
            }
            Tab.RECENTS -> {
                setKeypadShown(false)
                recentsFragment.onTabResumed()
            }
        }
    }

    // --- On-screen keypad state ------------------------------------------------

    private fun setKeypadShown(shown: Boolean) {
        binding.dialpadPanel.visibility = if (shown) View.VISIBLE else View.GONE
        // bottomNav stays visible at all times; the dialpad sits above it in the layout.
        val large = Prefs.screenProfile(this) == Prefs.PROFILE_LARGE
        barHider.enabled = !shown && !large
        if (!barHider.enabled) barHider.show()
        binding.dialpadPanel.post {
            // Pad the suggestions list enough to clear both the dialpad (when open)
            // and the always-visible nav bar beneath it.
            val navH = binding.bottomNav.height
            val fallback = (72 * resources.displayMetrics.density).toInt()
            val pad = if (shown) binding.dialpadPanel.height.coerceAtLeast(fallback) + navH else navH
            dialerFragment.setSuggestionsBottomPadding(pad)
        }
    }

    private fun applyKeypadSetting() {
        applyBigKeypad()
        if (Prefs.keypadDefaultOpen(this) && !didAutoOpenKeypad) {
            didAutoOpenKeypad = true
            setKeypadShown(true)
            return
        }
        if (binding.dialpadPanel.visibility == View.VISIBLE) return
        setKeypadShown(false)
    }

    private fun applyBigKeypad() {
        val big = Prefs.bigKeypad(this)
        val density = resources.displayMetrics.density
        val keyHeight = ((if (big) 76 else 56) * density).toInt()
        val digitSp = if (big) 32f else 26f
        val lettersSp = if (big) 16f else 12f
        for (key in dialKeys) {
            key.layoutParams = key.layoutParams.apply { height = keyHeight }
            (key.getChildAt(0) as? android.widget.TextView)
                ?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, digitSp)
            (key.getChildAt(1) as? android.widget.TextView)
                ?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, lettersSp)
        }
        binding.btnDialBig.visibility = if (big) View.VISIBLE else View.GONE
    }

    // --- Hardware keys + intent routing ----------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentTab == Tab.DIALER) {
            if (dialerFragment.handleKey(event)) return true
        } else if (!recentsFragment.isSearchFocused() &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        ) {
            val ch = dialCharFor(event.keyCode)
            if (ch != null) {
                showTab(Tab.DIALER)
                dialerFragment.insertInitial(ch)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dialCharFor(keyCode: Int): Char? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'
        KeyEvent.KEYCODE_POUND -> '#'
        else -> null
    }

    private fun routeIntent(intent: Intent?) {
        intent ?: return
        val filter = intent.getStringExtra(EXTRA_FILTER)
        when {
            filter != null -> {
                intent.removeExtra(EXTRA_FILTER)
                showTab(Tab.RECENTS)
                recentsFragment.applyFilter(filter)
            }
            intent.getStringExtra(EXTRA_START_TAB) == TAB_DIALER || isDialIntent(intent) -> {
                showTab(Tab.DIALER)
                dialerFragment.prefillFromIntent(intent)
            }
            // The call/send button (CALL_BUTTON) and the launcher icon (MAIN) both
            // always open the call log, even if the app was already running and
            // was left on the dialer or elsewhere — singleTask brings the existing
            // instance back via onNewIntent rather than a fresh onCreate, so this
            // has to be forced explicitly rather than relying on the tab default.
            intent.action == Intent.ACTION_CALL_BUTTON || intent.action == Intent.ACTION_MAIN ->
                showTab(Tab.RECENTS)
        }
    }

    private fun isDialIntent(intent: Intent): Boolean {
        if (intent.data?.scheme == "tel") return true
        if (intent.action == Intent.ACTION_DIAL && intent.extras?.get("key_value") != null) return true
        return false
    }

    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    companion object {
        private const val TAG_RECENTS = "recents"
        private const val TAG_DIALER = "dialer"
        private const val TAG_VOICEMAIL = "voicemail"
        const val EXTRA_START_TAB = "start_tab"
        const val TAB_DIALER = "dialer"
        const val EXTRA_FILTER = "filter"
        const val FILTER_INCOMING = "incoming"
        const val FILTER_OUTGOING = "outgoing"
        const val FILTER_MISSED = "missed"
    }
}
