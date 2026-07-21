package com.puretech.dialer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Small anchored popup (same M3 rounded-card shell as [CardMenu]) for picking
 * which number to call when a favorite contact has more than one. A "Don't ask
 * again" checkbox lets the user remember the chosen number as that contact's
 * default going forward.
 */
class NumberPickerPopup(private val context: Context, private val anchor: View) {

    private val numbers = ArrayList<ContactNumber>()
    private var headerTitle: CharSequence? = null
    private var listener: ((String, Boolean) -> Unit)? = null

    fun title(text: CharSequence): NumberPickerPopup { headerTitle = text; return this }

    fun numbers(list: List<ContactNumber>): NumberPickerPopup { numbers.addAll(list); return this }

    /** Called with (number, rememberAsDefault) when a number row is tapped. */
    fun onPick(l: (String, Boolean) -> Unit): NumberPickerPopup { listener = l; return this }

    fun show() {
        val inflater = LayoutInflater.from(context)
        val card = inflater.inflate(R.layout.popup_menu_card, null) as ViewGroup
        val container = card.findViewById<LinearLayout>(R.id.container)

        val popup = PopupWindow(
            card, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true
        )

        headerTitle?.let { text ->
            val header = inflater.inflate(R.layout.item_popup_menu_title, container, false)
            header.findViewById<TextView>(R.id.title).text = text
            container.addView(header)
        }

        // Inflated once and read at pick time, so any row can see whether the
        // user checked "don't ask again" before tapping a number.
        val checkboxRow = inflater.inflate(R.layout.item_popup_checkbox, container, false)
        val checkbox = checkboxRow.findViewById<MaterialCheckBox>(R.id.checkbox)
        checkboxRow.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }

        for (n in numbers) {
            val rowView = inflater.inflate(R.layout.item_popup_menu_number, container, false)
            rowView.findViewById<TextView>(R.id.number).text = n.number
            rowView.findViewById<TextView>(R.id.label).text = n.label
            rowView.setOnClickListener {
                popup.dismiss()
                listener?.invoke(n.number, checkbox.isChecked)
            }
            container.addView(rowView)
        }

        container.addView(checkboxRow)

        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 8f * context.resources.displayMetrics.density
        popup.isOutsideTouchable = true

        // Place the card above the anchor (favorite avatars sit near the top of
        // Recents, same reasoning CardMenu uses for the in-call controls).
        card.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val yOff = -(anchor.height + card.measuredHeight)
        popup.showAsDropDown(anchor, 0, yOff, Gravity.START)
    }
}
