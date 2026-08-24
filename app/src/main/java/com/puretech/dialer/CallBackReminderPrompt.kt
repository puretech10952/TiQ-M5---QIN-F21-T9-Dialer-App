package com.puretech.dialer

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Calendar

/**
 * Shown right after a number is added to the call-back list ("Call back
 * later"): asks whether the user wants a reminder, and if so lets them pick
 * a date/time plus write a note — the note is the same one shown on the
 * call-back list entry (see [StarredStore.updateNotes]).
 */
object CallBackReminderPrompt {

    fun show(context: Context, number: String, name: String?) {
        val label = name?.ifBlank { null } ?: number
        dialogBuilder(context)
            .setTitle(R.string.reminder_prompt_title)
            .setMessage(context.getString(R.string.reminder_prompt_message, label))
            .setPositiveButton(R.string.reminder_prompt_yes) { _, _ -> showSetupDialog(context, number) }
            .setNegativeButton(R.string.reminder_prompt_no, null)
            .show()
    }

    /** M3 rounded corners + pill-shaped action buttons for this whole flow. */
    private fun dialogBuilder(context: Context) =
        MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_M5Dialer_RoundedDialog)

    private fun showSetupDialog(context: Context, number: String) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        var chosen: Calendar? = null

        val pickField = TextView(context).apply {
            text = context.getString(R.string.reminder_pick_datetime)
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
            setTextColor(context.themeColor(com.google.android.material.R.attr.colorPrimary))
        }
        val noteField = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = context.getString(R.string.notes_hint)
            minLines = 3
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(pickField)
            addView(noteField)
        }

        pickField.setOnClickListener { pickDateTime(context, chosen) { cal ->
            chosen = cal
            pickField.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(cal.time)
        } }

        dialogBuilder(context)
            .setTitle(R.string.reminder_setup_title)
            .setView(container)
            .setPositiveButton(R.string.notes_save) { _, _ ->
                val at = chosen?.timeInMillis
                val note = noteField.text?.toString().orEmpty()
                val app = context.applicationContext
                Thread {
                    if (note.isNotBlank()) StarredStore.updateNotes(app, number, note)
                    if (at != null) {
                        StarredStore.setReminderAt(app, number, at)
                        CallBackReminderScheduler.schedule(app, number, at)
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickDateTime(context: Context, current: Calendar?, onPicked: (Calendar) -> Unit) {
        val now = Calendar.getInstance()
        val base = (current ?: (now.clone() as Calendar).apply { add(Calendar.HOUR_OF_DAY, 1) })
        DatePickerDialog(
            context,
            { _, y, m, d ->
                val withDate = (base.clone() as Calendar).apply {
                    set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, d)
                }
                TimePickerDialog(
                    context,
                    { _, h, min ->
                        withDate.set(Calendar.HOUR_OF_DAY, h)
                        withDate.set(Calendar.MINUTE, min)
                        withDate.set(Calendar.SECOND, 0)
                        onPicked(withDate)
                    },
                    base.get(Calendar.HOUR_OF_DAY), base.get(Calendar.MINUTE), false
                ).show()
            },
            base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = now.timeInMillis - 60_000L
        }.show()
    }
}
