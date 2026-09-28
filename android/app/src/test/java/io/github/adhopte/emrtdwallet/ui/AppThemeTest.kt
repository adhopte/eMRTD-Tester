package io.github.adhopte.emrtdwallet.ui

import androidx.appcompat.app.AppCompatActivity
import io.github.adhopte.emrtdwallet.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** MainActivity is an AppCompatActivity, which refuses to start unless its theme is AppCompat-based. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AppThemeTest {
    @Test
    fun appThemeStartsAnAppCompatActivity() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_GetYourIdWallet)
        controller.setup()
    }
}
