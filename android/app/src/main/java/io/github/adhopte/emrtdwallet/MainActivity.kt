package io.github.adhopte.emrtdwallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.adhopte.emrtdwallet.ui.AppNavigation
import io.github.adhopte.emrtdwallet.ui.WalletTheme
import io.github.adhopte.emrtdwallet.wallet.OfferController

/** A link that opened the wallet: a presentation request or a credential offer. */
sealed interface IncomingLink {
    data class Presentation(val uri: Uri) : IncomingLink
    data class CredentialOffer(val uri: String) : IncomingLink
}

// AppCompatActivity: a FragmentActivity subclass (required by BiometricPrompt) that also gives
// AppCompatDelegate.setApplicationLocales (Settings > language) automatic locale application and
// recreation below API 33.
class MainActivity : AppCompatActivity() {

    private val app get() = application as WalletApp

    private var pendingLink by mutableStateOf<IncomingLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            WalletTheme {
                AppNavigation(
                    incoming = pendingLink,
                    onIncomingConsumed = { pendingLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.lock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) app.lock.onBackground()
    }

    override fun onResume() {
        super.onResume()
        // Allow proximity readers to engage by tapping (ISO 18013-5 NFC engagement)
        runCatching { app.wallet.enableNFCEngagement(this) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { app.wallet.disableNFCEngagement(this) }
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val text = uri.toString()
        when {
            // OpenID4VCI authorization code flow: the browser returns to the wallet
            text.startsWith(OfferController.AUTH_REDIRECT) -> app.repository.offers.resumeAuthorization(uri)
            OfferController.isCredentialOffer(text) -> pendingLink = IncomingLink.CredentialOffer(text)
            uri.scheme in REMOTE_SCHEMES -> pendingLink = IncomingLink.Presentation(uri)
            else -> return
        }
        setIntent(Intent())
    }

    companion object {
        val REMOTE_SCHEMES = setOf("openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp", "mdoc")
    }
}
