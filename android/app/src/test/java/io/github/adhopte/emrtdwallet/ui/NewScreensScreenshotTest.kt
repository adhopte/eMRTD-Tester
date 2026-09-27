package io.github.adhopte.emrtdwallet.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.adhopte.emrtdwallet.data.ActivityEntry
import io.github.adhopte.emrtdwallet.data.ActivityLog
import io.github.adhopte.emrtdwallet.data.ActivityType
import io.github.adhopte.emrtdwallet.security.WalletLock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the new wallet screens on the JVM for review (`./gradlew :app:testDebugUnitTest`). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xxhdpi", application = android.app.Application::class)
class NewScreensScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun securitySetup() {
        val lock = WalletLock(ctx)
        compose.setContent { WalletTheme { SetupSecurityScreen(lock, ActivityLog(File(ctx.cacheDir, "a.json")), false) {} } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_security_setup.png")
    }

    @Test
    fun lockScreenPin() {
        val lock = WalletLock(ctx).apply { setPin("902741") }
        compose.setContent { WalletTheme { LockScreen(lock) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_lock_pin.png")
    }

    @Test
    fun activityHistory() {
        val now = System.currentTimeMillis()
        val entries = listOf(
            ActivityEntry(time = now - 60_000, type = ActivityType.PRESENTED, title = "Shared 3 attribute(s)",
                detail = "Online (OpenID4VP)", party = "verifier.eudiw.dev", items = listOf("Family name", "Given names", "Over 18")),
            ActivityEntry(time = now - 3_600_000, type = ActivityType.ISSUED, title = "Added Photo ID, Age verification",
                party = "IN Groupe Issuer (TEST)", items = listOf("Photo ID", "Age verification")),
            ActivityEntry(time = now - 3_700_000, type = ActivityType.PRESENTATION_DECLINED, title = "Declined a request",
                party = "Unknown verifier"),
            ActivityEntry(time = now - 90_000_000, type = ActivityType.WALLET_CREATED, title = "Wallet initialised",
                detail = "Wallet unit 3f2a9c1b · protected by a wallet PIN"),
        )
        compose.setContent { WalletTheme { ActivityHistory(entries, {}, {}) } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_activity.png")
    }

    @Test
    fun offeredCards() {
        compose.setContent {
            WalletTheme {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IssuerHeader("IN Groupe Issuer (TEST)")
                    OfferedCredentialCard("Person Identification Data (PID)", "eu.europa.ec.eudi.pid.1")
                    OfferedCredentialCard("Photo ID", "org.iso.23220.photoID.1")
                    OfferedCredentialCard("Age verification", "eu.europa.ec.av.1")
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/new_offer_cards.png")
    }
}
