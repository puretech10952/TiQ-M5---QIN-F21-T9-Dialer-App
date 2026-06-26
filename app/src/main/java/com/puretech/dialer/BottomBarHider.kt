package com.puretech.dialer

import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Makes a bottom bar follow the list scroll: it slides down in proportion to how
 * far/fast you scroll down (leaving a thin sliver), and slides back as you scroll
 * up, snapping fully open at the very top. Attach to the screen's RecyclerView;
 * disable it (e.g. while the on-screen keypad is open) via [enabled].
 */
class BottomBarHider(
    private val bar: View,
    sliverDp: Float = 6f
) : RecyclerView.OnScrollListener() {

    private val sliver = sliverDp * bar.resources.displayMetrics.density
    var enabled = true

    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
        if (!enabled) return
        // At the very top, the bar is always fully shown.
        if (!rv.canScrollVertically(-1)) {
            bar.translationY = 0f
            return
        }
        // Move with the scroll: down hides toward the sliver, up brings it back.
        val max = (bar.height - sliver).coerceAtLeast(0f)
        bar.translationY = (bar.translationY + dy).coerceIn(0f, max)
    }

    /** Reset the bar to fully shown (used when the on-screen keypad closes). */
    fun show() {
        if (bar.translationY != 0f) bar.animate().translationY(0f).setDuration(160).start()
    }
}
