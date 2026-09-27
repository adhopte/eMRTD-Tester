package io.github.adhopte.emrtdwallet.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val auth = rememberAuthenticator(vm.lock)
    val title = when (state) {
        is PresentationState.QrReady -> "Share in person"
        is PresentationState.AwaitingConsent -> "Review request"
        else -> "Share"
    }
    Scaffold(topBar = { SimpleTopBar(title, onClose) }) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                PresentationState.Idle, PresentationState.Connecting -> Waiting("Connecting to the verifier…")
                is PresentationState.QrReady -> {
                    Text("Let the verifier scan this code", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Card(shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Image(s.qr.asImageBitmap(), "Device engagement QR code", Modifier.padding(16.dp).size(280.dp))
                    }
                    Text("Nothing is shared yet: you will see who is asking and what, and decide.",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Hint(Icons.Filled.Bluetooth, "Keep Bluetooth on")
                        if (rememberNfcStatus() == NfcStatus.ENABLED) Hint(Icons.Filled.Nfc, "Or tap an NFC reader")
                    }
                }
                PresentationState.Connected -> Waiting("Connected — waiting for the verifier's request…")
                is PresentationState.AwaitingConsent -> Consent(
                    s.request,
                    onAccept = { option -> auth.request("Share your data") { presentation.accept(option) } },
                    onReject = { presentation.reject(); onClose() },
                )
                PresentationState.Sending -> Waiting("Sending your data securely…")
                is PresentationState.Done -> {
                    Spacer(Modifier.height(24.dp))
                    Icon(Icons.Filled.CheckCircle, null, Modifier.size(88.dp), tint = Color(0xFF2E7D32))
                    Text("Shared successfully", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    s.verifier?.let { Text("with $it", style = MaterialTheme.typography.titleMedium) }
                    if (s.shared.isNotEmpty()) Text("${s.shared.size} attribute(s): ${s.shared.joinToString()}",
                        textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    s.redirectUri?.let { uri ->
                        Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) },
                            modifier = Modifier.fillMaxWidth()) { Text("Continue on the website") }
                    }
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                is PresentationState.Failed -> {
                    Spacer(Modifier.height(24.dp))
                    Icon(Icons.Filled.Error, null, Modifier.size(80.dp), tint = MaterialTheme.colorScheme.error)
                    Text("Sharing failed", style = MaterialTheme.typography.headlineSmall)
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Spacer(Modifier.height(48.dp))
    CircularProgressIndicator()
    Text(text, textAlign = TextAlign.Center)
}

@Composable
private fun Hint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(" $text", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Consent(request: ConsentRequest, onAccept: (Int) -> Unit, onReject: () -> Unit) {
    var option by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(
                    if (request.verifierTrusted) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color(0xFFF9A825).copy(alpha = 0.18f),
                    CircleShape), contentAlignment = Alignment.Center) {
                    Icon(if (request.verifierTrusted) Icons.Filled.VerifiedUser else Icons.Filled.GppMaybe, null,
                        tint = if (request.verifierTrusted) Color(0xFF2E7D32) else Color(0xFFB77900))
                }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(request.verifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (request.verifierTrusted) "Registered relying party" else "Identity not confirmed by a trusted list",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (request.verifierTrusted) Color(0xFF2E7D32) else Color(0xFFB77900))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(Icons.Filled.Language, null, Modifier.size(14.dp))
                        Text(" ${request.transport}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Text("wants to see", style = MaterialTheme.typography.titleSmall)
        if (request.options.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                request.options.indices.forEach { i ->
                    FilterChip(option == i, { option = i }, label = { Text("Option ${i + 1}") })
                }
            }
        }
        request.options[option].groupBy { it.document.ifBlank { "Your credential" } }.forEach { (doc, items) ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(doc, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    items.forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                        else Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = InGroupe.SkyBlue)
                            Text(claimLabel(item.element).takeIf { it != item.element } ?: item.displayName,
                                Modifier.padding(start = 10.dp).weight(1f))
                            if (item.intentToRetain) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Storage, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                Text(" stored", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        Text("Only the attributes above are shared, signed by your wallet. Check the verifier before you share.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Decline") }
            Button(onClick = { onAccept(option) }, modifier = Modifier.weight(1f)) { Text("Share") }
        }
    }
}
