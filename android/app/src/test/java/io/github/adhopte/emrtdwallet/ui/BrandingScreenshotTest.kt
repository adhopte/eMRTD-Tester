package io.github.adhopte.emrtdwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the branded tutorial on the JVM (Robolectric native graphics) so the look can be
 * reviewed without a device: `./gradlew :app:recordRoborazziDebug` writes PNGs to
 * app/build/outputs/roborazzi/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class BrandingScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tutorialPages() {
        compose.mainClock.autoAdvance = false
        compose.setContent { WalletTheme { Box(Modifier.fillMaxSize()) { TutorialScreen(onFinish = {}) } } }
        // Page-specific moments inside each looping animation
        val moments = listOf(700L, 2300L, 2600L, 900L, 1500L, 2400L)
        moments.forEachIndexed { page, t ->
            compose.mainClock.advanceTimeBy(t)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/tutorial_${page + 1}.png")
            if (page < moments.lastIndex) {
                compose.onNodeWithText("Next").performClick()
                compose.mainClock.advanceTimeBy(800)
            }
        }
    }
}
