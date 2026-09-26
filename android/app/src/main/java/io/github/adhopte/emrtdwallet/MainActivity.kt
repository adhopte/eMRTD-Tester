package io.github.adhopte.emrtdwallet

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.adhopte.emrtdwallet.ui.AppNavigation
import io.github.adhopte.emrtdwallet.ui.WalletTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as WalletApp

    /** A presentation request (OpenID4VP / ISO 18013-7) that arrived via deep link. */
    private var pendingRemoteRequest by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            WalletTheme {
                AppNavigation(
                    remoteRequest = pendingRemoteRequest,
                    onRemoteRequestConsumed = { pendingRemoteRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
        if (uri.scheme in REMOTE_SCHEMES) {
            pendingRemoteRequest = uri
            setIntent(Intent())
        }
    }

    private companion object {
        val REMOTE_SCHEMES = setOf("openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp", "mdoc")
    }
}

