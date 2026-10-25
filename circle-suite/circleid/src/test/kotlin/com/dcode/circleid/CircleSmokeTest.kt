package com.dcode.circleid

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dcode.circle.protocol.CircleContract
import com.dcode.circleid.lab.SensorProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CircleSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardingRefusesToEnrollWithoutAStrongBiometric() {
        rule.onNodeWithText("Set up Circle ID").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("Teach Circle your face").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Begin").performClick()
        // Without a Class 3 biometric enrolment must stop with guidance; with one it must ask for the camera
        // (permission is not pre-granted on the test device). Either way nothing may crash.
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText("fingerprint in system settings", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Camera access").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun sensorProbeReportsWithoutCrashing() {
        val results = SensorProbe.run(ApplicationProvider.getApplicationContext())
        assertTrue(results.any { it.group == "Biometric API" })
        assertTrue(results.any { it.group == "Kernel device nodes" })
        assertTrue(results.any { it.group == "Ultrasonic sensor" })
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class VerifyActivityGuardTest {
    @Test
    fun requestsWithoutAVerifiableCallerAreRejected() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), VerifyActivity::class.java)
            .setAction(CircleContract.ACTION_VERIFY)
            .putExtra(CircleContract.EXTRA_REQUEST, "{\"garbage\":true}".toByteArray())
        ActivityScenario.launchActivityForResult<VerifyActivity>(intent).use { s ->
            assertEquals(Activity.RESULT_CANCELED, s.result.resultCode)
        }
    }
}
