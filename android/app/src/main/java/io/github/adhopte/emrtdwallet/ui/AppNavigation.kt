package io.github.adhopte.emrtdwallet.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val MRZ = "mrz"
    const val NFC = "nfc"
    const val SCAN = "scan"
    const val RESULT = "result"
    const val DOCUMENT = "document/{id}"
    const val PRESENT = "present"
    const val SETTINGS = "settings"
    fun document(id: String) = "document/${Uri.encode(id)}"
}

@Composable
fun AppNavigation(remoteRequest: Uri?, onRemoteRequestConsumed: () -> Unit) {
    val nav = rememberNavController()
    val vm: MainViewModel = viewModel()

    LaunchedEffect(remoteRequest) {
        if (remoteRequest != null) {
            vm.repository.presentation.startRemote(remoteRequest)
            onRemoteRequestConsumed()
            nav.navigate(Routes.PRESENT) { launchSingleTop = true }
        }
    }

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onAdd = { vm.warmUpIssuer(); nav.navigate(Routes.ADD) },
                onOpen = { nav.navigate(Routes.document(it)) },
                onPresent = {
                    vm.repository.presentation.startProximity()
                    nav.navigate(Routes.PRESENT)
                },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ADD) {
            AddPidScreen(
                onChip = { nav.navigate(Routes.MRZ) },
                onScan = { vm.resetIssuance(); nav.navigate(Routes.SCAN) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.MRZ) {
            MrzScreen(vm = vm, onContinue = { vm.resetIssuance(); nav.navigate(Routes.NFC) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.NFC) {
            NfcReadScreen(
                vm = vm,
                onDone = { nav.navigate(Routes.RESULT) },
                onUseImageScan = {
                    vm.resetIssuance()
                    nav.navigate(Routes.SCAN) { popUpTo(Routes.ADD) }
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.SCAN) {
            DocumentScanScreen(vm = vm, onDone = { nav.navigate(Routes.RESULT) }, onBack = { nav.popBackStack() })
        }
        composable(Routes.RESULT) {
            ResultScreen(vm = vm, onFinish = {
                vm.resetIssuance()
                nav.popBackStack(Routes.HOME, inclusive = false)
            })
        }
        composable(Routes.DOCUMENT) { entry ->
            DocumentScreen(vm = vm, id = Uri.decode(entry.arguments?.getString("id").orEmpty()), onBack = { nav.popBackStack() })
        }
        composable(Routes.PRESENT) {
            PresentScreen(vm = vm, onClose = {
                vm.repository.presentation.reset()
                nav.popBackStack(Routes.HOME, inclusive = false)
            })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}
