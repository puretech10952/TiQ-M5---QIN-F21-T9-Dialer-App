package com.puretech.dialer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.util.Log

/**
 * Holds the PROXIMITY_SCREEN_OFF_WAKE_LOCK for the WHOLE duration of an earpiece
 * call — not just while the in-call screen is foreground. Because the wake lock
 * is a system-level lock owned by [CallService] (which lives for the entire
 * call), the screen blanks when the phone is held to the ear no matter what app
 * is on screen, and lights back up when moved away.
 *
 * Driven by [CallManager] state: active call + earpiece route → acquire,
 * otherwise → release.
 *
 * When [ProximityAccessibilityService] is enabled AND [Prefs.proximityLatchEnabled]
 * is turned on (its own opt-in switch -- the permission alone also powers the
 * unrelated manual in-call Screen off button, and must not silently change
 * earpiece behavior on its own), that last part (lights back up when moved
 * away) is instead handled by [latch mode][startLatchMode]: some
 * devices' proximity sensors misfire and report "far" for a moment while the
 * phone is still pressed to the caller's ear, which relights the screen and
 * lets a cheek trigger mute/hold/hang up. In latch mode we don't touch the
 * special wake lock at all -- instead, on "near" we lock the screen directly
 * via the accessibility service's GLOBAL_ACTION_LOCK_SCREEN, a reliable public
 * API. Since nothing is monitoring the sensor for power purposes at that
 * point, the screen physically cannot relight on its own; only a real
 * hardware key (power button) wakes it, which is exactly the "stays off until
 * a physical button is pressed" behavior this is for.
 */
object ProximityController : CallManager.Listener {

    private const val TAG = "ProximityController"

    private var wakeLock: PowerManager.WakeLock? = null
    private var appContext: Context? = null

    // --- Latch mode -------------------------------------------------------

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var latchActive = false
    private var latchArmed = true
    private var screenReceiver: BroadcastReceiver? = null

    /** Start watching call state (called when the first call is added). */
    fun attach(context: Context) {
        appContext = context.applicationContext
        CallManager.registerListener(this)   // also fires onCallChanged() immediately
    }

    /** Stop watching and release the screen (called when the last call ends). */
    fun detach() {
        CallManager.unregisterListener(this)
        stopLatchMode()
        release()
    }

    override fun onCallChanged() {
        val shouldBlank = CallManager.activeCall() != null && CallManager.isOnEarpiece()
        // Latch mode requires BOTH the accessibility permission AND its own
        // explicit opt-in (Prefs) -- the permission alone is also what powers
        // the unrelated manual in-call Screen off button (Speaker/Bluetooth),
        // and granting it for that must not silently disable the normal
        // automatic proximity screen on/off at the ear.
        val strict = appContext?.let {
            Prefs.proximityLatchEnabled(it) && ProximityAccessibilityService.isEnabled(it)
        } == true
        Log.i(TAG, "onCallChanged shouldBlank=$shouldBlank strict=$strict")

        if (!strict) {
            stopLatchMode()
            if (shouldBlank) acquire() else release()
            return
        }

        // Latch mode owns the screen entirely via the accessibility service;
        // never hold the plain wake lock alongside it.
        release()
        if (shouldBlank) startLatchMode() else stopLatchMode()
    }

    private fun acquire() {
        val ctx = appContext ?: return
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return
        if (wakeLock == null) {
            if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                Log.w(TAG, "PROXIMITY_SCREEN_OFF_WAKE_LOCK not supported on this device")
                return
            }
            wakeLock = pm.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "m5dialer:proximity"
            )
        }
        if (wakeLock?.isHeld == false) {
            Log.i(TAG, "acquire()")
            wakeLock?.acquire(60 * 60 * 1000L)
        }
    }

    private fun release() {
        if (wakeLock?.isHeld == true) {
            Log.i(TAG, "release()")
            wakeLock?.release()
        }
    }

    private fun startLatchMode() {
        if (latchActive) return
        val ctx = appContext ?: return
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sensor == null) {
            Log.w(TAG, "no TYPE_PROXIMITY sensor -- latch mode unavailable, falling back to plain hold")
            acquire()
            return
        }

        sensorManager = sm
        proximitySensor = sensor
        latchArmed = true
        latchActive = true
        Log.i(TAG, "startLatchMode range=${sensor.maximumRange}")

        sm.registerListener(latchSensorListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    // We hold no wake lock in latch mode, so the only way the
                    // screen turns on is a genuine hardware wake.
                    Log.i(TAG, "ACTION_SCREEN_ON -- re-arming")
                    latchArmed = true
                }
            }
        }
        screenReceiver = receiver
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    private fun stopLatchMode() {
        if (!latchActive) return
        Log.i(TAG, "stopLatchMode")
        latchActive = false
        sensorManager?.unregisterListener(latchSensorListener)
        sensorManager = null
        proximitySensor = null
        screenReceiver?.let { appContext?.unregisterReceiver(it) }
        screenReceiver = null
        latchArmed = true
    }

    private val latchSensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val near = event.values.isNotEmpty() &&
                event.values[0] < (proximitySensor?.maximumRange ?: 5f)
            Log.i(TAG, "sensor value=${event.values.getOrNull(0)} near=$near armed=$latchArmed")
            if (near && latchArmed) {
                val locked = ProximityAccessibilityService.lockScreen()
                Log.i(TAG, "lockScreen() -> $locked")
                if (locked) latchArmed = false
            }
            // "far" is deliberately ignored here -- see the class doc.
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
}
