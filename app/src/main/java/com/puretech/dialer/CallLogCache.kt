package com.puretech.dialer

import android.content.Context

/**
 * Caches the last full (unfiltered) Recents list so reopening the app after a
 * call doesn't have to sit through this device's slow CallLog.Calls provider
 * query (profiled at multiple seconds on this ROM) before showing the call
 * that just ended. [prefetch] is fired the instant a call ends (from
 * [InCallServiceImpl]) — well before the user has actually navigated back to
 * Recents — so that slow query runs hidden behind call teardown / the user
 * looking at their screen, instead of after they've already opened the tab.
 * [RecentsFragment.reload] paints this immediately when available, then still
 * runs its own fresh load right after to catch anything the cache missed.
 */
object CallLogCache {
    @Volatile
    var entries: List<CallLogEntry>? = null
        private set

    @Volatile
    private var loading = false

    fun prefetch(context: Context) {
        if (loading) return
        loading = true
        val ctx = context.applicationContext
        Thread {
            try {
                entries = CallLogRepository.load(ctx)
            } finally {
                loading = false
            }
        }.start()
    }

    /** Keeps the cache in sync with whatever [RecentsFragment.reload] actually
     *  finds, so a delete/edit or a prefetch that missed something doesn't keep
     *  getting served stale on the next tab open. */
    fun store(list: List<CallLogEntry>) {
        entries = list
    }
}
