package com.puretech.dialer

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityIncomingCallGestureBinding

/** Calls > Incoming call gesture: how to answer/decline an incoming call --
 *  a single tap on round buttons (default) or a sideways swipe -- plus,
 *  only relevant to the tap style, which side the Answer button sits on. */
class IncomingCallGestureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallGestureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingCallGestureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        if (Prefs.incomingCallGesture(this) == Prefs.GESTURE_SWIPE) binding.gestureSwipe.isChecked = true
        else binding.gestureTap.isChecked = true
        updateAnswerSideVisibility()

        binding.gestureGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setIncomingCallGesture(
                this,
                if (checkedId == R.id.gestureSwipe) Prefs.GESTURE_SWIPE else Prefs.GESTURE_TAP
            )
            updateAnswerSideVisibility()
        }

        if (Prefs.answerButtonSide(this) == Prefs.ANSWER_SIDE_RIGHT) binding.answerSideRight.isChecked = true
        else binding.answerSideLeft.isChecked = true

        binding.answerSideGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setAnswerButtonSide(
                this,
                if (checkedId == R.id.answerSideRight) Prefs.ANSWER_SIDE_RIGHT else Prefs.ANSWER_SIDE_LEFT
            )
        }
    }

    private fun updateAnswerSideVisibility() {
        val visible = Prefs.incomingCallGesture(this) != Prefs.GESTURE_SWIPE
        val vis = if (visible) View.VISIBLE else View.GONE
        binding.answerSideLabel.visibility = vis
        binding.answerSideCard.visibility = vis
    }
}
