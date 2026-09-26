package io.github.adhopte.emrtdwallet.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.adhopte.emrtdwallet.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.format.MsoMdocData
import io.github.adhopte.emrtdwallet.wallet.PID_NAMESPACE
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandTopBar(actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_ingroupe_emblem), null, Modifier.size(32.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("by IN Groupe", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        actions = { actions() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
    )
}

fun IssuedDocument.claims(): Map<String, Map<String, Any?>> =
    (data as? MsoMdocData)?.nameSpacedDataDecoded ?: emptyMap()

fun IssuedDocument.pid(): Map<String, Any?> = claims()[PID_NAMESPACE].orEmpty()

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onPresent: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
) {
    val docs by vm.repository.documents.collectAsState()
    val blePermissions = rememberBlePermissions()
    Scaffold(
        topBar = {
            BrandTopBar(actions = {
                IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "How it works") }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add PID") })
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (docs.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(painterResource(R.drawable.logo_ingroupe), "IN Groupe", Modifier.height(72.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No PID yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Add a Person Identification Data credential from your passport or ID card.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            items(docs, key = { it.id }) { doc -> PidCard(doc) { onOpen(doc.id) } }
            if (docs.isNotEmpty()) {
                item {
                    Button(
                        onClick = { blePermissions.request(); onPresent() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.QrCode, null)
                        Text("Show QR to a proximity verifier", Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "Web verifiers: scan their QR code with the camera app, or open their link on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 96.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun PidCard(doc: IssuedDocument, onClick: () -> Unit) {
    val pid = remember(doc.id) { doc.pid() }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(InGroupe.CardGradient))) {
            // Emblem watermark
            Image(
                painterResource(R.drawable.ic_ingroupe_emblem), null,
                Modifier.size(150.dp).align(Alignment.CenterEnd).offset(x = 40.dp).alpha(0.18f),
            )
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_ingroupe_emblem), null, Modifier.size(28.dp))
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text("Person Identification Data", color = Color.White,
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("Issued by IN Groupe · ${pid["issuing_country"] ?: "EU"}", color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text("PID", color = InGroupe.Red, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color.White, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White)) {
                        Portrait(pid["portrait"] as? ByteArray, 72)
                    }
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text("${pid["given_name"] ?: ""} ${pid["family_name"] ?: ""}".trim(), color = Color.White,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Born ${formatValue(pid["birth_date"])}", color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall)
                        Text("Valid until ${formatValue(pid["expiry_date"])}", color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.bodySmall)
                        Text(doc.name, color = InGroupe.SkyBlue, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun Portrait(bytes: ByteArray?, sizeDp: Int) {
    val bmp = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.size(sizeDp.dp), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Portrait")
        else Icon(Icons.Filled.Badge, null, Modifier.size((sizeDp * 0.7).dp))
    }
}

fun formatValue(v: Any?): String = when (v) {
    null -> "—"
    is ByteArray -> "${v.size} bytes"
    is Boolean -> if (v) "yes" else "no"
    is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}: ${formatValue(it.value)}" }
    is Collection<*> -> v.joinToString(", ") { formatValue(it) }
    else -> v.toString()
}

@Composable
fun AddPidScreen(onChip: () -> Unit, onScan: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val nfc = rememberNfcStatus()
    Scaffold(topBar = { SimpleTopBar("Add PID", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("How do you want to prove your identity?", style = MaterialTheme.typography.titleMedium)
            if (nfc == NfcStatus.UNAVAILABLE) {
                // Image scan first: it is the only path that works on this phone
                OptionCard(
                    icon = Icons.Filled.CameraAlt,
                    title = "Scan your passport or ID card",
                    body = "Photograph the data page (and the back of ID cards). The issuer checks image " +
                        "authenticity and MRZ/VIZ consistency.",
                    onClick = onScan,
                )
                OptionCard(
                    icon = Icons.Filled.Nfc,
                    title = "Read the document chip",
                    body = "Not available: this phone has no NFC reader. Chip reading (highest assurance) " +
                        "needs an NFC-capable phone.",
                    enabled = false,
                    onClick = {},
                )
                return@Column
            }
            OptionCard(
                icon = Icons.Filled.Nfc,
                title = "Passport / ID card with chip",
                body = "Scan the MRZ, then hold the document to the phone. The issuer verifies the chip " +
                    "(Passive, Active and Chip Authentication). Highest assurance." +
                    if (nfc == NfcStatus.DISABLED) "\nNFC is switched off — tap to turn it on." else "",
                onClick = { if (nfc == NfcStatus.DISABLED) openNfcSettings(context) else onChip() },
            )
            OptionCard(
                icon = Icons.Filled.CameraAlt,
                title = "Document without chip — scan images",
                body = "Photograph the data page (and the back of ID cards). The issuer checks image " +
                    "authenticity heuristics and MRZ/VIZ consistency. Lower assurance.",
                onClick = onScan,
            )
        }
    }
}

@Composable
private fun OptionCard(icon: ImageVector, title: String, body: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick)) {
        Row(Modifier.padding(16.dp)) {
            Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
fun DocumentScreen(vm: MainViewModel, id: String, onBack: () -> Unit) {
    val doc = remember(id) { vm.repository.document(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        SimpleTopBar("PID details", onBack) {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }) { padding ->
        if (doc == null) {
            Text("Document not found", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val claims = remember(doc.id) { doc.claims() }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Portrait(claims[PID_NAMESPACE]?.get("portrait") as? ByteArray, 120)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(doc.name, style = MaterialTheme.typography.titleMedium)
                    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
                    Text("Issued ${fmt.format(doc.issuedAt)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            claims.forEach { (ns, elements) ->
                Text(ns, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
                HorizontalDivider()
                elements.filterKeys { it != "portrait" }.forEach { (name, value) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(name, Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall)
                        Text(formatValue(value), Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this PID?") },
                text = { Text("The credential and its device key are removed from this phone.") },
                confirmButton = { TextButton(onClick = { vm.repository.delete(doc.id); onBack() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf(vm.settings.issuerUrl) }
    Scaffold(topBar = { SimpleTopBar("Settings", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("PID issuer backend URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.settings.issuerUrl = url; onBack() }) { Text("Save") }
            Text(
                "Verifiers must trust the issuer's test IACA certificate, available at $url/pki/iaca.pem",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
