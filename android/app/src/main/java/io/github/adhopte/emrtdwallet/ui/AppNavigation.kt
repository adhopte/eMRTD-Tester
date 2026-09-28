package io.github.adhopte.emrtdwallet.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.adhopte.emrtdwallet.IncomingLink
import io.github.adhopte.emrtdwallet.R

object Routes {
    const val TUTORIAL = "tutorial"
    const val SETUP_SECURITY = "setup_security"
    const val CHANGE_SECURITY = "change_security"
    const val HOME = "home"
    const val ADD = "add"
    const val MRZ = "mrz"
    const val NFC = "nfc"
    const val SCAN = "scan"
    const val SELFIE = "selfie"
    const val RESULT = "result"
    const val DOCUMENT = "document/{id}"
    const val PRESENT = "present"
    const val QR = "qr"
    const val OFFER = "offer"
    const val ACTIVITY = "activity"
    fun document(id: String) = "document/${Uri.encode(id)}"
}

@Composable
fun AppNavigation(incoming: IncomingLink?, onIncomingConsumed: () -> Unit) {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel()
    val locked by vm.lock.locked.collectAsState()
    val blePermissions = rememberBlePermissions()

    fun openOffer(uri: String) {
        vm.offers.resolve(uri)
        nav.navigate(Routes.OFFER) { launchSingleTop = true; popUpTo(Routes.HOME) }
    }

    fun openPresentation(uri: Uri) {
        vm.repository.presentation.startRemote(uri)
        nav.navigate(Routes.PRESENT) { launchSingleTop = true; popUpTo(Routes.HOME) }
    }

    fun showQr() {
        blePermissions.request()
        vm.repository.presentation.startProximity()
        nav.navigate(Routes.PRESENT) { launchSingleTop = true }
    }

    LaunchedEffect(incoming) {
        when (incoming) {
            is IncomingLink.Presentation -> openPresentation(incoming.uri)
            is IncomingLink.CredentialOffer -> openOffer(incoming.uri)
            null -> return@LaunchedEffect
        }
        onIncomingConsumed()
    }

    val start = remember {
        when {
            !vm.settings.tutorialSeen -> Routes.TUTORIAL
            !vm.lock.initialized -> Routes.SETUP_SECURITY
            else -> Routes.HOME
        }
    }
    Box(Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = start) {
            composable(Routes.TUTORIAL) {
                TutorialScreen(onFinish = {
                    vm.settings.tutorialSeen = true
                    when {
                        nav.previousBackStackEntry != null -> nav.popBackStack() // opened from Help
                        !vm.lock.initialized -> nav.navigate(Routes.SETUP_SECURITY) { popUpTo(Routes.TUTORIAL) { inclusive = true } }
                        else -> nav.navigate(Routes.HOME) { popUpTo(Routes.TUTORIAL) { inclusive = true } }
                    }
                })
            }
            composable(Routes.SETUP_SECURITY) {
                SetupSecurityScreen(vm.lock, vm.activity, changing = false) {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.SETUP_SECURITY) { inclusive = true } }
                }
            }
            composable(Routes.CHANGE_SECURITY) {
                val auth = rememberAuthenticator(vm.lock, forSharing = false)
                var confirmed by remember { mutableStateOf(false) }
                val changeSecurityReason = stringResource(R.string.auth_reason_change_security)
                LaunchedEffect(Unit) { auth.request(changeSecurityReason) { confirmed = true } }
                if (confirmed) SetupSecurityScreen(vm.lock, vm.activity, changing = true) { nav.popBackStack() }
                else TutorialBackdrop()
            }
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onAdd = { vm.warmUpIssuer(); nav.navigate(Routes.ADD) },
                    onOpen = { nav.navigate(Routes.document(it)) },
                    onScan = { nav.navigate(Routes.QR) },
                    onShowQr = { showQr() },
                    onHelp = { nav.navigate(Routes.TUTORIAL) },
                    onActivity = { nav.navigate(Routes.ACTIVITY) },
                    onChangeSecurity = { nav.navigate(Routes.CHANGE_SECURITY) },
                )
            }
            composable(Routes.ADD) {
                AddPidScreen(
                    vm = vm,
                    onChip = { nav.navigate(Routes.MRZ) },
                    onScan = { vm.resetIssuance(); nav.navigate(Routes.SCAN) },
                    onQr = { nav.navigate(Routes.QR) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.MRZ) {
                MrzScreen(vm = vm, onContinue = { vm.resetIssuance(); nav.navigate(Routes.NFC) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.NFC) {
                NfcReadScreen(
                    vm = vm,
                    onDone = { nav.navigate(Routes.SELFIE) },
                    onUseImageScan = {
                        vm.resetIssuance()
                        nav.navigate(Routes.SCAN) { popUpTo(Routes.ADD) }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SCAN) {
                DocumentScanScreen(vm = vm, onDone = { nav.navigate(Routes.SELFIE) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.SELFIE) {
                SelfieScreen(
                    vm = vm,
                    onSubmitted = { nav.navigate(Routes.RESULT) { popUpTo(Routes.HOME) } },
                    onBack = { vm.resetIssuance(); nav.popBackStack(Routes.ADD, inclusive = false) },
                )
            }
            composable(Routes.RESULT) {
                ResultScreen(
                    vm = vm,
                    onFinish = { vm.resetIssuance(); nav.popBackStack(Routes.HOME, inclusive = false) },
                    onOffer = { uri -> vm.resetIssuance(); openOffer(uri) },
                    onRetry = { vm.resetIssuance(); nav.navigate(Routes.ADD) { popUpTo(Routes.HOME) } },
                )
            }
            composable(Routes.DOCUMENT) { entry ->
                DocumentScreen(
                    vm = vm,
                    id = Uri.decode(entry.arguments?.getString("id").orEmpty()),
                    onShowQr = { showQr() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.QR) {
                ScanQrScreen(
                    onTarget = { target ->
                        when (target) {
                            is ScanTarget.Offer -> openOffer(target.uri)
                            is ScanTarget.Presentation -> openPresentation(Uri.parse(target.uri))
                            is ScanTarget.Unsupported -> Unit
                        }
                    },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.OFFER) {
                OfferScreen(vm = vm, onClose = { nav.goHome() }, onOpenWallet = { nav.goHome() })
            }
            composable(Routes.PRESENT) {
                PresentScreen(vm = vm, onClose = {
                    vm.repository.presentation.reset()
                    nav.goHome()
                })
            }
            composable(Routes.ACTIVITY) {
                ActivityScreen(vm = vm, onBack = { nav.popBackStack() })
            }
        }
        if (locked && vm.lock.initialized) LockScreen(vm.lock)
    }
}

private fun NavHostController.goHome() {
    if (!popBackStack(Routes.HOME, inclusive = false)) navigate(Routes.HOME)
}

@Composable
private fun TutorialBackdrop() {
    Box(Modifier.fillMaxSize())
}
