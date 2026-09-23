package com.example

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  /**
   * Golden-image comparison depends on the machine that recorded the reference PNG
   * (font rasterisation differs between operating systems), so this test is opt-in
   * instead of failing an otherwise healthy CI run. Run it locally with:
   *
   *     RUN_SCREENSHOT_TESTS=true gradle testDebugUnitTest
   */
  @Before
  fun requireScreenshotOptIn() {
    val enabled =
      System.getenv("RUN_SCREENSHOT_TESTS").equals("true", ignoreCase = true) ||
        System.getProperty("roborazzi.test.record") == "true"
    Assume.assumeTrue("Screenshot verification skipped (set RUN_SCREENSHOT_TESTS=true to run).", enabled)
  }

  @Test
  fun greeting_screenshot() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Text("WhatsApp Gateway Native Android")
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/greeting.png")
  }
}

