package io.github.adhopte.emrtdwallet.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.adhopte.emrtdwallet.wallet.ConsentRequest
import io.github.adhopte.emrtdwallet.wallet.PresentationState

class BlePermissions(val request: () -> Unit)

/** BLE permissions needed for ISO 18013-5 proximity presentation (Android 12+). */
@Composable
fun rememberBlePermissions(): BlePermissions {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    return remember {
        BlePermissions {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            launcher.launch(perms)
        }
    }
}

@Composable
fun PresentScreen(vm: MainViewModel, onClose: () -> Unit) {
    val presentation = vm.repository.presentation
    val state by presentation.state.collectAsState()
    val context = LocalContext.current
    Scaffold(topBar = { SimpleTopBar("Share PID", onClose) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                PresentationState.Idle, PresentationState.Connecting -> {
                    CircularProgressIndicator()
                    Text("Waiting for the verifier…")
                }
                is PresentationState.QrReady -> {
                    Text("Let the verifier scan this code", style = MaterialTheme.typography.titleMedium)
                    Image(s.qr.asImageBitmap(), "Device engagement QR code", Modifier.size(300.dp))
                    Text("ISO/IEC 18013-5 device engagement over BLE. Keep Bluetooth on. " +
                        "NFC readers can also engage by tapping the phone.",
                        style = MaterialTheme.typography.bodySmall)
                }
                PresentationState.Connected -> {
                    CircularProgressIndicator()
                    Text("Connected — waiting for the request…")
                }
                is PresentationState.AwaitingConsent -> Consent(
                    s.request,
                    onAccept = { presentation.accept(it) },
                    onReject = { presentation.reject(); onClose() },
                )
                PresentationState.Sending -> {
                    CircularProgressIndicator()
                    Text("Sending response…")
                }
                is PresentationState.Done -> {
                    Text("Shared successfully", style = MaterialTheme.typography.titleLarge)
                    s.redirectUri?.let { uri ->
                        Button(onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        }) { Text("Return to the verifier") }
                    }
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
                is PresentationState.Failed -> {
                    Text("Presentation failed", style = MaterialTheme.typography.titleLarge)
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Consent(request: ConsentRequest, onAccept: (Int) -> Unit, onReject: () -> Unit) {
    var option by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(request.verifier, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            (if (request.verifierTrusted) "Verified relying party" else "Verifier identity not verified by a trusted list") +
                " · ${request.transport}",
            style = MaterialTheme.typography.bodySmall,
            color = if (request.verifierTrusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (request.options.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                request.options.indices.forEach { i ->
                    FilterChip(option == i, { option = i }, label = { Text("Option ${i + 1}") })
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Requested from your PID", style = MaterialTheme.typography.labelLarge)
                request.options[option].forEach { item ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(item.displayName, Modifier.weight(1f))
                        if (item.intentToRetain) Text("will be stored", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReject) { Text("Decline") }
            Button(onClick = { onAccept(option) }) { Text("Share") }
        }
    }
}
