package com.puretech.dialer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import java.util.concurrent.Executors

/**
 * Avatar rendering. Three states, in priority order:
 *   1. a contact photo (loaded off the main thread, cached, recycle-safe)
 *   2. a colored circle with the name's first initial
 *   3. a grey person icon for unknown numbers
 *
 * Photos are decoded on a small background pool and kept in an LruCache, so the
 * UI never blocks on disk I/O and a fast scroll can't leave the wrong photo on a
 * recycled row: every bind stamps the target view with a key and a finished
 * decode is only applied if that key still matches.
 */
object Avatars {

    private val PALETTE = intArrayOf(
        0xFFDB4437.toInt(), 0xFFE67C00.toInt(), 0xFFF09300.toInt(), 0xFF0B8043.toInt(),
        0xFF009688.toInt(), 0xFF1A73E8.toInt(), 0xFF3F51B5.toInt(), 0xFF673AB7.toInt(),
        0xFFC2185B.toInt(), 0xFF795548.toInt(), 0xFF607D8B.toInt(), 0xFFAD1457.toInt()
    )
    private const val GREY = 0xFF5F6368.toInt()
    private const val GREY_BG = 0xFFE3E5E8.toInt()
    private const val TARGET_PX = 192

    // ~1/8 of the heap, sized by bitmap byte count.
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(2048)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // A tiny decode pool — contact photos are small and this is bursty during scroll.
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private fun colorFor(key: String): Int {
        if (key.isBlank()) return GREY
        var h = 0
        for (c in key) h = h * 31 + c.code
        return PALETTE[Math.floorMod(h, PALETTE.size)]
    }

    private fun initialOf(name: String?): Char? =
        name?.trim()?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()

    /**
     * Configure an [initial] TextView (colored circle) and a [photo]
     * ShapeableImageView for one contact/number.
     */
    fun bind(initial: TextView, photo: ShapeableImageView, name: String?, photoUri: Uri?) {
        val key = photoUri?.toString().orEmpty()
        // Stamp the target so a late decode for a previous binding is ignored.
        photo.setTag(R.id.avatar_key, key)

        if (photoUri == null) {
            // No photo: a colored initial or a grey person, full size.
            showPlaceholder(initial, photo, name)
            return
        }

        // Cached photo: show it straight away, no flicker.
        cache.get(key)?.let { showBitmap(initial, photo, it); return }

        // Photo still loading: show a FULL-SIZE neutral placeholder (the initial, or a
        // plain grey circle) so the avatar never shrinks to the small padded icon and
        // then jumps back when the photo arrives.
        showLoadingPlaceholder(initial, photo, name)

        executor.execute {
            val bmp = decode(photo.context.applicationContext, photoUri)
            if (bmp != null) cache.put(key, bmp)
            main.post {
                // Only apply if this view still wants this exact photo.
                if (photo.getTag(R.id.avatar_key) == key) {
                    if (bmp != null) showBitmap(initial, photo, bmp)
                    else showPlaceholder(initial, photo, name)  // decode failed
                }
            }
        }
    }

    /** Full-size placeholder while a photo loads: colored initial if we have a name,
     *  otherwise a plain grey circle (no small padded icon → no size jump). */
    private fun showLoadingPlaceholder(initial: TextView, photo: ShapeableImageView, name: String?) {
        val ch = initialOf(name)
        if (ch != null) {
            photo.setImageDrawable(null)
            photo.visibility = View.GONE
            initial.text = ch.toString()
            initial.backgroundTintList = ColorStateList.valueOf(colorFor(name ?: ""))
            initial.visibility = View.VISIBLE
        } else {
            initial.visibility = View.GONE
            photo.setImageDrawable(null)
            photo.setPadding(0, 0, 0, 0)
            photo.scaleType = ImageView.ScaleType.CENTER_CROP
            photo.setBackgroundResource(R.drawable.bg_circle_solid)
            photo.backgroundTintList = ColorStateList.valueOf(GREY_BG)
            photo.imageTintList = null
            photo.visibility = View.VISIBLE
        }
    }

    /** Colored initial (named) or grey person (unknown) — shown instantly. */
    private fun showPlaceholder(initial: TextView, photo: ShapeableImageView, name: String?) {
        photo.setImageDrawable(null)
        val ch = initialOf(name)
        if (ch != null) {
            initial.text = ch.toString()
            initial.backgroundTintList = ColorStateList.valueOf(colorFor(name ?: ""))
            initial.visibility = View.VISIBLE
            photo.visibility = View.GONE
        } else {
            val pad = (10 * photo.resources.displayMetrics.density).toInt()
            photo.scaleType = ImageView.ScaleType.FIT_CENTER
            photo.setPadding(pad, pad, pad, pad)
            photo.setBackgroundResource(R.drawable.bg_circle_solid)
            photo.backgroundTintList = ColorStateList.valueOf(GREY_BG)
            photo.imageTintList = ColorStateList.valueOf(GREY)
            photo.setImageResource(R.drawable.ic_person)
            photo.visibility = View.VISIBLE
            initial.visibility = View.GONE
        }
    }

    private fun showBitmap(initial: TextView, photo: ShapeableImageView, bmp: Bitmap) {
        photo.background = null
        photo.backgroundTintList = null
        photo.imageTintList = null
        photo.setPadding(0, 0, 0, 0)
        photo.scaleType = ImageView.ScaleType.CENTER_CROP
        photo.setImageBitmap(bmp)
        photo.visibility = View.VISIBLE
        initial.visibility = View.GONE
    }

    /** Decode a (possibly large) contact photo down to ~TARGET_PX, off the main thread. */
    private fun decode(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > TARGET_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (e: Throwable) {
        null
    }
}
