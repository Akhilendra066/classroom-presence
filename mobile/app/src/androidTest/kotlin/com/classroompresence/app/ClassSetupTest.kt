package com.classroompresence.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.classroompresence.core.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClassSetupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun render(calibrated: Boolean, start: (SessionTiming) -> Unit = {}) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PresenceTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SessionSetupContent(ClassInfo("setup-test", "Test class", "ROOM_A101"),
                            Defaults.config.copy(calibrated = calibrated), false, start, {})
                    }
                }
            }
        }
    }
    @Test fun savedCalibrationAllowsValidatedTimingWithoutInternalDetails() {
        var chosen: SessionTiming? = null
        render(true) { chosen = it }
        compose.onNodeWithText("Room ready").assertExists()
        compose.onAllNodesWithText("checkpoint", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithTag("minimum-presence").performTextReplacement("37")
        compose.onAllNodesWithText("rounded", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onNodeWithTag("class-duration").performTextReplacement("30")
        compose.onNodeWithText("Start class").assertIsNotEnabled()
        compose.onNodeWithTag("minimum-presence").performTextReplacement("17")
        compose.onNodeWithText("Start class").assertIsEnabled().performScrollTo().performClick()
        assertEquals(SessionTiming(30, 17), chosen)
    }
    @Test fun uncalibratedRoomCannotStartClass() {
        render(false)
        compose.onNodeWithText("Room calibration required").assertExists()
        compose.onNodeWithText("Start class").assertIsNotEnabled()
        compose.onNodeWithText("Calibrate room").assertExists()
    }
}
