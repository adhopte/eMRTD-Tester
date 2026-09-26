package io.github.adhopte.emrtdwallet

import android.app.Application
import eu.europa.ec.eudi.iso18013.transfer.response.ReaderAuthPolicy
import eu.europa.ec.eudi.wallet.EudiWallet
import eu.europa.ec.eudi.wallet.EudiWalletConfig
import eu.europa.ec.eudi.wallet.logging.Logger
import eu.europa.ec.eudi.wallet.transfer.openId4vp.ClientIdScheme
import eu.europa.ec.eudi.wallet.transfer.openId4vp.Format
import io.github.adhopte.emrtdwallet.data.AppSettings
import io.github.adhopte.emrtdwallet.data.IssuerApi
import io.github.adhopte.emrtdwallet.wallet.PidNfcEngagementService
import io.github.adhopte.emrtdwallet.wallet.WalletRepository
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security
import kotlin.time.Duration.Companion.seconds

class WalletApp : Application() {

    lateinit var wallet: EudiWallet
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var repository: WalletRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // JMRTD needs the full BouncyCastle provider (brainpool curves, ISO 9797 MACs, ...);
        // Android ships a stripped-down one under the same name.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        settings = AppSettings(this)
        val config = EudiWalletConfig()
            .configureDocumentManager(File(noBackupFilesDir, "pid-wallet.db").absolutePath)
            .configureLogging(level = Logger.LEVEL_INFO)
            // Demo defaults: keys live in the Android Keystore but do not require biometric unlock
            .configureDocumentKeyCreation(
                userAuthenticationRequired = false,
                userAuthenticationTimeout = 30.seconds,
                useStrongBoxForKeys = false,
            )
            // Verifier (reader) certificates are shown to the user but never block disclosure;
            // the user decides on the consent screen.
            .configureReaderAuthPolicy(ReaderAuthPolicy.DoNotEnforce)
            .configureProximityPresentation(
                enableBlePeripheralMode = true,
                enableBleCentralMode = true,
                clearBleCache = true,
                nfcEngagementServiceClass = PidNfcEngagementService::class.java,
            )
            .configureOpenId4Vp {
                withClientIdSchemes(
                    ClientIdScheme.X509SanDns,
                    ClientIdScheme.X509Hash,
                    ClientIdScheme.RedirectUri,
                )
                withSchemes("openid4vp", "eudi-openid4vp", "mdoc-openid4vp", "haip-vp")
                withFormats(Format.MsoMdoc.ES256)
            }
        wallet = EudiWallet(this, config)
        repository = WalletRepository(this, wallet, IssuerApi { settings.issuerUrl })
    }
}
