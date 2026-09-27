package io.github.adhopte.emrtdwallet.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.europa.ec.eudi.wallet.document.IssuedDocument
import eu.europa.ec.eudi.wallet.document.format.MsoMdocData
import eu.europa.ec.eudi.wallet.document.format.MsoMdocFormat
import io.github.adhopte.emrtdwallet.BuildConfig
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.security.LockMethod
import io.github.adhopte.emrtdwallet.wallet.PID_NAMESPACE
import io.github.adhopte.emrtdwallet.wallet.docTypeName
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// Document helpers
// ---------------------------------------------------------------------------

fun IssuedDocument.claims(): Map<String, Map<String, Any?>> =
    (data as? MsoMdocData)?.nameSpacedDataDecoded ?: emptyMap()

fun IssuedDocument.pid(): Map<String, Any?> = claims()[PID_NAMESPACE].orEmpty()

val IssuedDocument.docType: String get() = (format as? MsoMdocFormat)?.docType.orEmpty()

private fun IssuedDocument.firstClaim(vararg names: String): Any? {
    val all = claims().values
    for (n in names) all.firstNotNullOfOrNull { it[n] }?.let { return it }
    return null
}

fun IssuedDocument.portraitBytes(): ByteArray? =
    IMAGE_CLAIMS.firstNotNullOfOrNull { imageBytes(firstClaim(it)) }

fun IssuedDocument.holderName(): String? {
    val given = firstClaim("given_name", "given_name_unicode")?.toString()
    val family = firstClaim("family_name", "family_name_unicode")?.toString()
    return listOfNotNull(given, family).joinToString(" ").ifBlank { null }
}

fun IssuedDocument.expiryText(): String =
    firstClaim("date_of_expiry", "expiry_date")?.let(::formatValue)
        ?: runCatching { DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(validUntil) }.getOrDefault("—")

data class DocStyle(val badge: String, val gradient: List<Color>, val icon: ImageVector)

fun docStyle(docType: String): DocStyle = when (docType) {
    "eu.europa.ec.eudi.pid.1" -> DocStyle("PID", listOf(InGroupe.Navy, InGroupe.MediumBlue), Icons.Filled.Badge)
    "org.iso.23220.photoID.1" -> DocStyle("PHOTO ID", listOf(InGroupe.MediumBlue, InGroupe.SkyBlue), Icons.Filled.Badge)
    "eu.europa.ec.av.1" -> DocStyle("AGE 18+", listOf(InGroupe.DarkRed, InGroupe.Red), Icons.Filled.Lock)
    "org.iso.18013.5.1.mDL" -> DocStyle("mDL", listOf(Color(0xFF0B5E52), Color(0xFF1A9E8A)), Icons.Filled.Badge)
    else -> DocStyle("ATTESTATION", listOf(Color(0xFF3B4A7A), Color(0xFF6D7DB3)), Icons.Filled.Badge)
}

private val CLAIM_LABELS = mapOf(
    "family_name" to "Family name", "given_name" to "Given names", "birth_date" to "Date of birth",
    "family_name_unicode" to "Family name", "given_name_unicode" to "Given names",
    "place_of_birth" to "Place of birth", "birth_place" to "Place of birth", "nationality" to "Nationality",
    "sex" to "Sex", "resident_address" to "Address", "personal_administrative_number" to "Personal number",
    "age_over_18" to "Over 18", "age_over_21" to "Over 21", "age_in_years" to "Age", "age_birth_year" to "Year of birth",
    "issuing_authority" to "Issued by", "issuing_authority_unicode" to "Issued by", "issuing_country" to "Issuing country",
    "document_number" to "Document number", "travel_document_number" to "Travel document number",
    "person_id" to "Personal number", "date_of_issuance" to "Issue date", "date_of_expiry" to "Expiry date",
    "issuance_date" to "Issue date (legacy)", "expiry_date" to "Expiry date", "issue_date" to "Issue date",
    "evidence_type" to "Evidence", "source_document_type" to "Source document", "source_document_issuing_state" to "Document country",
    "source_document_expiry" to "Document expiry", "verification_checks" to "Checks",
    "dtc_version" to "DTC version", "dtc_sod" to "eMRTD security object", "dtc_dg1" to "eMRTD DG1", "dtc_dg2" to "eMRTD DG2",
)

private val NAMESPACE_LABELS = mapOf(
    "eu.europa.ec.eudi.pid.1" to "Identity",
    "org.emrtd-tester.evidence.1" to "How it was verified",
    "org.iso.23220.1" to "Identity",
    "org.iso.23220.photoID.1" to "Photo ID",
    "org.iso.23220.dtc.1" to "Digital Travel Credential (chip data)",
    "eu.europa.ec.av.1" to "Age",
)

fun claimLabel(name: String): String =
    CLAIM_LABELS[name] ?: Regex("age_over_(\\d+)").matchEntire(name)?.let { "Over ${it.groupValues[1]}" }
    ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }

fun formatValue(v: Any?): String = when (v) {
    null -> "—"
    is ByteArray -> "${v.size} bytes"
    is Boolean -> if (v) "Yes" else "No"
    is Map<*, *> -> v.entries.joinToString(", ") { "${it.key}: ${formatValue(it.value)}" }
    is Collection<*> -> v.joinToString(", ") { formatValue(it) }
    is String -> if (v.length > 120 && v.none { it == ' ' }) "${v.length * 3 / 4} bytes (binary)" else v
    else -> v.toString()
}

// ---------------------------------------------------------------------------
// Shell: bottom navigation
// ---------------------------------------------------------------------------

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

enum class HomeTab { WALLET, SETTINGS }

@Composable
fun HomeScreen(
    vm: MainViewModel,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    onShowQr: () -> Unit,
    onHelp: () -> Unit,
    onActivity: () -> Unit,
    onChangeSecurity: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.WALLET) }
    val docs by vm.repository.documents.collectAsState()
    var noDocs by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            BrandTopBar(actions = {
                IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "How it works") }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == HomeTab.WALLET, { tab = HomeTab.WALLET },
                    icon = { Icon(Icons.Filled.Wallet, null) }, label = { Text("Wallet") })
                NavigationBarItem(false, onScan, icon = { Icon(Icons.Filled.QrCodeScanner, null) }, label = { Text("Scan") })
                NavigationBarItem(false, { if (docs.isEmpty()) noDocs = true else onShowQr() },
                    icon = { Icon(Icons.Filled.QrCode, null) }, label = { Text("Show QR") })
                NavigationBarItem(tab == HomeTab.SETTINGS, { tab = HomeTab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                HomeTab.WALLET -> WalletTab(docs, onAdd, onOpen, onScan, onShowQr)
                HomeTab.SETTINGS -> SettingsTab(vm, onActivity, onChangeSecurity, onHelp)
            }
        }
    }
    if (noDocs) AlertDialog(
        onDismissRequest = { noDocs = false },
        title = { Text("Nothing to share yet") },
        text = { Text("Add your PID first, then show the QR code to a verifier in person.") },
        confirmButton = { TextButton(onClick = { noDocs = false; onAdd() }) { Text("Add PID") } },
        dismissButton = { TextButton(onClick = { noDocs = false }) { Text("Later") } },
    )
}

@Composable
private fun WalletTab(
    docs: List<IssuedDocument>,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit,
    onShowQr: () -> Unit,
) {
    val holder = remember(docs) { docs.firstOrNull { it.docType == "eu.europa.ec.eudi.pid.1" }?.holderName() }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(top = 4.dp)) {
                Text(if (holder != null) "Hello, ${holder.substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() }}"
                    else "Welcome", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (docs.isEmpty()) "Let's add your first credential" else "${docs.size} credential(s) in your wallet",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Filled.QrCodeScanner, "Scan QR", "Share online · Get credentials", Modifier.weight(1f), onScan)
                QuickAction(Icons.Filled.QrCode, "Show QR", "Share in person", Modifier.weight(1f), onShowQr, enabled = docs.isNotEmpty())
            }
        }
        if (docs.isEmpty()) {
            item { EmptyWallet(onAdd) }
        }
        items(docs, key = { it.id }) { doc -> DocumentCard(doc) { onOpen(doc.id) } }
        item {
            Card(
                Modifier.fillMaxWidth().clickable(onClick = onAdd),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text("Add a document", fontWeight = FontWeight.SemiBold)
                        Text("Passport / ID card, or a credential offer from an issuer", style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Filled.ChevronRight, null)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit, enabled: Boolean = true) {
    Card(
        modifier.alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(InGroupe.Blue), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White)
            }
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text(subtitle, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

@Composable
private fun EmptyWallet(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onAdd), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_ingroupe), "IN Groupe", Modifier.height(64.dp))
            Spacer(Modifier.height(12.dp))
            Text("Your wallet is empty", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Get your Person Identification Data (PID) from your passport or ID card — with the chip for the " +
                    "highest assurance — then share it with websites and verifiers.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** A credential rendered as a card: PID, photo ID, age verification or any other attestation. */
@Composable
fun DocumentCard(doc: IssuedDocument, onClick: () -> Unit) {
    val style = docStyle(doc.docType)
    val portrait = remember(doc.id) { doc.portraitBytes() }
    val name = remember(doc.id) { doc.holderName() }
    val claims = remember(doc.id) { doc.claims() }
    Card(
        Modifier.fillMaxWidth().aspectRatio(1.586f).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(style.gradient))) {
            Image(
                painterResource(R.drawable.ic_ingroupe_emblem), null,
                Modifier.size(170.dp).align(Alignment.CenterEnd).offset(x = 50.dp).alpha(0.14f),
            )
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_ingroupe_emblem), null, Modifier.size(26.dp))
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(docTypeName(doc.docType), color = Color.White, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(doc.issuerLabel(), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(style.badge, color = style.gradient.first(), fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.background(Color.White, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.Bottom) {
                    if (portrait != null || doc.docType != "eu.europa.ec.av.1") {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) { Portrait(portrait, 78) }
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        if (doc.docType == "eu.europa.ec.av.1") {
                            val over = claims.values.flatMap { it.entries }.filter { it.key.startsWith("age_over_") && it.value == true }
                                .map { it.key.removePrefix("age_over_").toInt() }.maxOrNull()
                            Text(if (over != null) "Over $over" else "Age verified", color = Color.White,
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("No name or photo is shared", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(name ?: doc.name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            doc.firstClaimText("birth_date")?.let {
                                Text("Born $it", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("Valid until ${doc.expiryText()}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun IssuedDocument.firstClaimText(name: String): String? = firstClaim(name)?.let(::formatValue)

private fun IssuedDocument.issuerLabel(): String {
    val ev = claims()["org.emrtd-tester.evidence.1"]?.get("evidence_type")?.toString()
    val source = when (ev) {
        "emrtd_chip" -> " · from chip"
        "document_image" -> " · from document scan"
        "manual_entry_unverified" -> " · test data"
        else -> ""
    }
    val authority = firstClaim("issuing_authority", "issuing_authority_unicode")?.toString()
    return (authority?.let { if ("IN Groupe" in it) "IN Groupe" else it } ?: "Issuer") + source
}

@Composable
fun Portrait(bytes: ByteArray?, sizeDp: Int) {
    val bmp = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.size(width = (sizeDp * 0.8).dp, height = sizeDp.dp), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "Portrait", contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Badge, null, Modifier.size((sizeDp * 0.6).dp), tint = InGroupe.Grey)
    }
}

// ---------------------------------------------------------------------------
// Add a document
// ---------------------------------------------------------------------------

@Composable
fun AddPidScreen(vm: MainViewModel, onChip: () -> Unit, onScan: () -> Unit, onQr: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val nfc = rememberNfcStatus()
    Scaffold(topBar = { SimpleTopBar("Add a document", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Get your PID", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OptionCard(
                icon = Icons.Filled.Nfc,
                title = "Passport / ID card with chip",
                badge = "Highest assurance",
                body = when (nfc) {
                    NfcStatus.UNAVAILABLE -> "Not available: this phone has no NFC reader."
                    NfcStatus.DISABLED -> "Scan the MRZ, read the chip, take a selfie. NFC is off — tap to turn it on."
                    else -> "Scan the MRZ, hold the document to the phone, then take a live selfie. The issuer checks " +
                        "Passive, Active and Chip Authentication and matches your face with the chip photo."
                },
                enabled = nfc != NfcStatus.UNAVAILABLE,
                onClick = { if (nfc == NfcStatus.DISABLED) openNfcSettings(context) else onChip() },
            )
            OptionCard(
                icon = Icons.Filled.CameraAlt,
                title = "Scan document images",
                badge = "No chip needed",
                body = "Photograph or upload the data page (and the back of ID cards), then take a live selfie. " +
                    "The issuer checks the images, the MRZ and your face.",
                onClick = onScan,
            )
            Text("Other credentials", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
            OptionCard(
                icon = Icons.Filled.QrCodeScanner,
                title = "Scan an issuer's QR code",
                body = "Add a PID or attestation (photo ID, age verification…) offered by an issuer through " +
                    "OpenID4VCI. You may need the transaction code the issuer gives you.",
                onClick = onQr,
            )
            OptionCard(
                icon = Icons.Filled.Cloud,
                title = "Open the IN Groupe issuer portal",
                body = "Create a credential offer QR code in a browser (on a computer, then scan it here).",
                trailing = Icons.AutoMirrored.Filled.OpenInNew,
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.settings.issuerUrl + "/issuer"))) },
            )
        }
    }
}

@Composable
private fun OptionCard(
    icon: ImageVector,
    title: String,
    body: String,
    badge: String? = null,
    enabled: Boolean = true,
    trailing: ImageVector = Icons.Filled.ChevronRight,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false))
                    badge?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White,
                            modifier = Modifier.padding(start = 8.dp).background(InGroupe.Red, RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
                Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(trailing, null)
        }
    }
}

// ---------------------------------------------------------------------------
// Document details
// ---------------------------------------------------------------------------

@Composable
fun DocumentScreen(vm: MainViewModel, id: String, onShowQr: () -> Unit, onBack: () -> Unit) {
    val doc = remember(id) { vm.repository.document(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    val auth = rememberAuthenticator(vm.lock, forSharing = false)
    Scaffold(topBar = {
        SimpleTopBar(doc?.let { docTypeName(it.docType) } ?: "Document", onBack) {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }) { padding ->
        if (doc == null) {
            Text("Document not found", Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val claims = remember(doc.id) { doc.claims() }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DocumentCard(doc) {}
            OutlinedButton(onClick = onShowQr, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCode, null); Text("  Show QR to a verifier in person")
            }
            val fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
            Text("Added ${fmt.format(doc.issuedAt)} · ${doc.docType}", style = MaterialTheme.typography.labelSmall)
            claims.forEach { (ns, elements) ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(NAMESPACE_LABELS[ns] ?: ns, style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        elements.filterKeys { it !in IMAGE_CLAIMS }.forEach { (name, value) ->
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                            Row {
                                Text(claimLabel(name), Modifier.weight(0.45f), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                                Text(formatValue(value), Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete this credential?") },
                text = { Text("The credential and its device key are removed from this phone. You can get it again from the issuer.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        auth.request("Delete credential") { vm.repository.delete(doc.id); onBack() }
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun SettingsTab(vm: MainViewModel, onActivity: () -> Unit, onChangeSecurity: () -> Unit, onHelp: () -> Unit) {
    val context = LocalContext.current
    val entries by vm.activity.entries.collectAsState()
    val method by vm.lock.methodFlow.collectAsState()
    var editUrl by remember { mutableStateOf(false) }
    var authShare by remember { mutableStateOf(vm.lock.requireAuthToShare) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SettingsGroup("Activity") {
            SettingsRow(Icons.Filled.History, "Wallet activity history",
                if (entries.isEmpty()) "No activity yet" else "${entries.size} event(s) · latest: ${entries.first().title}",
                onClick = onActivity)
        }
        SettingsGroup("Security") {
            SettingsRow(Icons.Filled.Lock, "Unlock method", when (method) {
                LockMethod.PIN -> "Wallet PIN"
                LockMethod.BIOMETRIC -> "Biometrics / device screen lock"
                LockMethod.NONE -> "Not set"
            }, onClick = onChangeSecurity)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 40.dp)) {
                    Text("Confirm before sharing")
                    Text("Ask for your PIN or biometrics each time data is shared", style = MaterialTheme.typography.bodySmall)
                }
                Switch(authShare, { authShare = it; vm.lock.requireAuthToShare = it })
            }
        }
        SettingsGroup("Issuer") {
            SettingsRow(Icons.Filled.Cloud, "PID issuer backend", vm.settings.issuerUrl, onClick = { editUrl = true })
            SettingsRow(Icons.AutoMirrored.Filled.OpenInNew, "Issuer portal (credential offers)", "${vm.settings.issuerUrl}/issuer",
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.settings.issuerUrl + "/issuer"))) })
            SettingsRow(Icons.AutoMirrored.Filled.OpenInNew, "Issuer IACA certificate (for verifiers)", "${vm.settings.issuerUrl}/pki/iaca.pem",
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.settings.issuerUrl + "/pki/iaca.pem"))) })
        }
        SettingsGroup("About") {
            SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, "How it works", "Replay the tutorial", onClick = onHelp)
            SettingsRow(Icons.Filled.Info, "getYourID Wallet ${BuildConfig.VERSION_NAME}",
                "Wallet unit ${vm.lock.walletUnitId.take(8)} · test issuer, not for production use", onClick = null)
        }
    }
    if (editUrl) {
        var url by remember { mutableStateOf(vm.settings.issuerUrl) }
        AlertDialog(
            onDismissRequest = { editUrl = false },
            title = { Text("PID issuer backend") },
            text = { OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("URL") }) },
            confirmButton = { TextButton(onClick = { vm.settings.issuerUrl = url; editUrl = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editUrl = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { content() }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onClick != null) Icon(Icons.Filled.ChevronRight, null)
    }
}
