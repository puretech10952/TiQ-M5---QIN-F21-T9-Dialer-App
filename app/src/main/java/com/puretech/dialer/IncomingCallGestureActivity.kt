package com.puretech.dialer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.puretech.dialer.databinding.ActivityIncomingCallGestureBinding

/** Calls > Incoming call gesture: how to answer/decline an incoming call --
 *  a single tap on round buttons (default) or a sideways swipe. */
class IncomingCallGestureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallGestureBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityIncomingCallGestureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }

        if (Prefs.incomingCallGesture(this) == Prefs.GESTURE_SWIPE) binding.gestureSwipe.isChecked = true
        else binding.gestureTap.isChecked = true

        binding.gestureGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setIncomingCallGesture(
                this,
                if (checkedId == R.id.gestureSwipe) Prefs.GESTURE_SWIPE else Prefs.GESTURE_TAP
            )
        }
    }
}
