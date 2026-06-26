package com.puretech.dialer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * A small Material 3 rounded-card popup menu with an icon + label per row, used
 * for the in-call More and Audio-route menus. Anchors above the tapped button.
 * The current selection (e.g. the active audio route) is tinted with the accent.
 */
class CardMenu(private val context: Context, private val anchor: View) {

    private data class Item(val id: Int, val iconRes: Int, val title: CharSequence, val selected: Boolean)

    private val items = ArrayList<Item>()
    private var listener: ((Int) -> Unit)? = null
    /** Called when the popup closes (tap or outside dismiss) — used to reset the
     *  anchor's active state. */
    var onDismiss: (() -> Unit)? = null

    fun add(id: Int, iconRes: Int, title: CharSequence, selected: Boolean = false): CardMenu {
        items.add(Item(id, iconRes, title, selected))
        return this
    }

    fun onClick(l: (Int) -> Unit): CardMenu { listener = l; return this }

    fun show() {
        val inflater = LayoutInflater.from(context)
        val card = inflater.inflate(R.layout.popup_menu_card, null) as ViewGroup
        val container = card.findViewById<LinearLayout>(R.id.container)

        val accent = context.themeColor(com.google.android.material.R.attr.colorPrimary)
        val onSurface = context.themeColor(com.google.android.material.R.attr.colorOnSurface)
        val iconTint = context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val popup = PopupWindow(
            card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )

        for (item in items) {
            val rowView = inflater.inflate(R.layout.item_popup_menu, container, false)
            val icon = rowView.findViewById<ImageView>(R.id.icon)
            val label = rowView.findViewById<TextView>(R.id.label)
            icon.setImageResource(item.iconRes)
            icon.setColorFilter(if (item.selected) accent else iconTint)
            label.text = item.title
            label.setTextColor(if (item.selected) accent else onSurface)
            rowView.setOnClickListener {
                popup.dismiss()
                listener?.invoke(item.id)
            }
            container.addView(rowView)
        }

        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 8f * context.resources.displayMetrics.density
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { onDismiss?.invoke() }

        // Place the card above the anchor (in-call controls sit near the bottom).
        card.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val yOff = -(anchor.height + card.measuredHeight)
        popup.showAsDropDown(anchor, 0, yOff, Gravity.END)
    }
}
