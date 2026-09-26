package io.github.adhopte.emrtdwallet.wallet

import eu.europa.ec.eudi.iso18013.transfer.engagement.NfcEngagementService
import io.github.adhopte.emrtdwallet.WalletApp

/** Lets proximity (ISO 18013-5) readers start a session by tapping the phone. */
class PidNfcEngagementService : NfcEngagementService() {
    override val transferManager
        get() = (application as WalletApp).wallet.transferManager
}
