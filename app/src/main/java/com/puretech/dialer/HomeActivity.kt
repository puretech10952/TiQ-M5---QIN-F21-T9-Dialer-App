package com.puretech.dialer

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.puretech.dialer.databinding.ActivityHomeBinding

/**
 * Single host that keeps ONE persistent bottom bar (Home | Keypad) and swaps the
 * content above it between [RecentsFragment] and [DialerFragment]. The fragments
 * are added once and shown/hidden (never recreated), so the bar never blinks and
 * each tab keeps its state. The drawer and the on-screen keypad live here too.
 */
class HomeActivity : AppCompatActivity() {

    private enum class Tab { RECENTS, DIALER }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var recentsFragment: RecentsFragment
    private lateinit var dialerFragment: DialerFragment
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

        // Add both fragments once (synchronously so their views exist below).
        if (savedInstanceState == null) {
            recentsFragment = RecentsFragment()
            dialerFragment = DialerFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, recentsFragment, TAG_RECENTS)
                .add(R.id.fragmentContainer, dialerFragment, TAG_DIALER)
                .hide(dialerFragment)
                .commitNow()
        } else {
            recentsFragment = supportFragmentManager.findFragmentByTag(TAG_RECENTS) as RecentsFragment
            dialerFragment = supportFragmentManager.findFragmentByTag(TAG_DIALER) as DialerFragment
            currentTab = if (dialerFragment.isHidden) Tab.RECENTS else Tab.DIALER
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
        ensureBarHider()
        if (currentTab == Tab.DIALER) {
            applyKeypadSetting()
            dialerFragment.onTabResumed()
        } else {
            recentsFragment.onTabResumed()
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
                R.id.nav_updates -> startActivity(Intent(this, UpdateActivity::class.java))
                R.id.nav_about -> startActivity(Intent(this, AboutActivity::class.java))
            }
            binding.drawerLayout.closeDrawers()
            true
        }
    }

    fun openDrawer() = binding.drawerLayout.openDrawer(GravityCompat.START)

    private fun setupBottomBar() {
        // Set the initial selection before wiring the listener so it doesn't fire.
        binding.bottomNav.selectedItemId =
            if (currentTab == Tab.DIALER) R.id.tab_keypad else R.id.tab_home
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (!suppressNav) when (item.itemId) {
                R.id.tab_home -> showTab(Tab.RECENTS)
                R.id.tab_keypad -> showTab(Tab.DIALER)
            }
            true
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.tab_home -> recentsFragment.scrollToTopAndClearSearch()
                R.id.tab_keypad -> setKeypadShown(binding.dialpadPanel.visibility != View.VISIBLE)
            }
        }
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
                currentTab == Tab.RECENTS && recentsFragment.handleBack() -> { /* consumed */ }
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    /** Attach the single scroll-hider to both lists once their views exist. Only the
     *  visible tab's list scrolls, so one hider on both is safe. */
    private fun ensureBarHider() {
        if (barHiderAttached) return
        val r = recentsFragment.scrollTarget() ?: return
        val d = dialerFragment.scrollTarget() ?: return
        r.addOnScrollListener(barHider)
        d.addOnScrollListener(barHider)
        barHiderAttached = true
    }

    // --- Tab switching ---------------------------------------------------------

    private fun showTab(tab: Tab) {
        if (tab == currentTab) {
            if (tab == Tab.RECENTS) recentsFragment.scrollToTopAndClearSearch()
            return
        }
        supportFragmentManager.beginTransaction().apply {
            if (tab == Tab.DIALER) { show(dialerFragment); hide(recentsFragment) }
            else { show(recentsFragment); hide(dialerFragment) }
        }.commit()
        currentTab = tab
        suppressNav = true
        binding.bottomNav.selectedItemId = if (tab == Tab.DIALER) R.id.tab_keypad else R.id.tab_home
        suppressNav = false
        binding.drawerLayout.setDrawerLockMode(
            if (tab == Tab.DIALER) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            else DrawerLayout.LOCK_MODE_UNLOCKED
        )
        barHider.show()
        if (tab == Tab.DIALER) {
            applyKeypadSetting()
            dialerFragment.onTabResumed()
        } else {
            setKeypadShown(false)
            recentsFragment.onTabResumed()
        }
    }

    // --- On-screen keypad state ------------------------------------------------

    private fun setKeypadShown(shown: Boolean) {
        binding.dialpadPanel.visibility = if (shown) View.VISIBLE else View.GONE
        // bottomNav stays visible at all times; the dialpad sits above it in the layout.
        barHider.enabled = !shown
        if (!shown) barHider.show()
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
        for (key in dialKeys) {
            key.layoutParams = key.layoutParams.apply { height = keyHeight }
            (key.getChildAt(0) as? android.widget.TextView)
                ?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, digitSp)
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
            // The call/send button (CALL_BUTTON) always opens the call log, even if
            // the app was last left on the dialer or elsewhere.
            intent.action == Intent.ACTION_CALL_BUTTON -> showTab(Tab.RECENTS)
            // else: MAIN → stay on the default Recents tab.
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
        const val EXTRA_START_TAB = "start_tab"
        const val TAB_DIALER = "dialer"
        const val EXTRA_FILTER = "filter"
        const val FILTER_INCOMING = "incoming"
        const val FILTER_OUTGOING = "outgoing"
        const val FILTER_MISSED = "missed"
    }
}
