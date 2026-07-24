package com.puretech.dialer.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the app's cold-start path (splash -> HomeActivity -> Recents, then a
 * switch to the Keypad tab and back) on a connected device so ART can record
 * which methods run during startup. `./gradlew :app:generateBaselineProfile`
 * writes the result to app/src/main/baseline-prof.txt, which AGP embeds in the
 * APK so those methods are AOT-compiled instead of interpreted/JIT-compiled
 * live on every cold start (see App.kt / HomeActivity.kt startup timing).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.puretech.dialer") {
        pressHome()
        startActivityAndWait()

        // Let the Recents list finish its first load, then exercise the other
        // main tab too, so both hot paths end up in the profile.
        device.wait(Until.hasObject(By.res(packageName, "recents")), 5_000)
        device.findObject(By.res(packageName, "tab_keypad"))?.click()
        device.wait(Until.hasObject(By.res(packageName, "dialpadPanel")), 3_000)
        device.findObject(By.res(packageName, "tab_home"))?.click()
        device.waitForIdle()
    }
}
