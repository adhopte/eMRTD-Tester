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
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.material3.RadioButton
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

@Composable
fun IssuedDocument.expiryText(): String =
    firstClaim("date_of_expiry", "expiry_date")?.let { formatValue(it) }
        ?: runCatching { DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault()).format(validUntil) }.getOrDefault("—")

data class DocStyle(val badge: String, val gradient: List<Color>, val icon: ImageVector)

@Composable
fun docStyle(docType: String): DocStyle = when (docType) {
    "eu.europa.ec.eudi.pid.1" -> DocStyle(stringResource(R.string.badge_pid), listOf(Brand.Navy, Brand.MediumBlue), Icons.Filled.Badge)
    "org.iso.23220.photoID.1" -> DocStyle(stringResource(R.string.badge_photo_id), listOf(Brand.MediumBlue, Brand.SkyBlue), Icons.Filled.Badge)
    "eu.europa.ec.av.1" -> DocStyle(stringResource(R.string.badge_age_18), listOf(Brand.DarkRed, Brand.Red), Icons.Filled.Lock)
    "org.iso.18013.5.1.mDL" -> DocStyle(stringResource(R.string.badge_mdl), listOf(Color(0xFF0B5E52), Color(0xFF1A9E8A)), Icons.Filled.Badge)
    else -> DocStyle(stringResource(R.string.badge_attestation), listOf(Color(0xFF3B4A7A), Color(0xFF6D7DB3)), Icons.Filled.Badge)
}

@Composable
private fun claimLabelsMap(): Map<String, String> = mapOf(
    "family_name" to stringResource(R.string.claim_family_name), "given_name" to stringResource(R.string.claim_given_names),
    "birth_date" to stringResource(R.string.claim_birth_date),
    "family_name_unicode" to stringResource(R.string.claim_family_name), "given_name_unicode" to stringResource(R.string.claim_given_names),
    "place_of_birth" to stringResource(R.string.claim_place_of_birth), "birth_place" to stringResource(R.string.claim_place_of_birth),
    "nationality" to stringResource(R.string.claim_nationality),
    "sex" to stringResource(R.string.claim_sex), "resident_address" to stringResource(R.string.claim_address),
    "personal_administrative_number" to stringResource(R.string.claim_personal_number),
    "age_over_18" to stringResource(R.string.claim_over_18), "age_over_21" to stringResource(R.string.claim_over_21),
    "age_in_years" to stringResource(R.string.claim_age), "age_birth_year" to stringResource(R.string.claim_year_of_birth),
    "issuing_authority" to stringResource(R.string.claim_issued_by), "issuing_authority_unicode" to stringResource(R.string.claim_issued_by),
    "issuing_country" to stringResource(R.string.claim_issuing_country),
    "document_number" to stringResource(R.string.claim_document_number), "travel_document_number" to stringResource(R.string.claim_travel_document_number),
    "person_id" to stringResource(R.string.claim_personal_number), "date_of_issuance" to stringResource(R.string.claim_issue_date),
    "date_of_expiry" to stringResource(R.string.claim_expiry_date),
    "issuance_date" to stringResource(R.string.claim_issue_date_legacy), "expiry_date" to stringResource(R.string.claim_expiry_date),
    "issue_date" to stringResource(R.string.claim_issue_date),
    "evidence_type" to stringResource(R.string.claim_evidence), "source_document_type" to stringResource(R.string.claim_source_document),
    "source_document_issuing_state" to stringResource(R.string.claim_document_country),
    "source_document_expiry" to stringResource(R.string.claim_document_expiry), "verification_checks" to stringResource(R.string.claim_checks),
    "dtc_version" to stringResource(R.string.claim_dtc_version), "dtc_sod" to stringResource(R.string.claim_dtc_sod),
    "dtc_dg1" to stringResource(R.string.claim_dtc_dg1), "dtc_dg2" to stringResource(R.string.claim_dtc_dg2),
)

@Composable
private fun namespaceLabelsMap(): Map<String, String> = mapOf(
    "eu.europa.ec.eudi.pid.1" to stringResource(R.string.namespace_identity),
    "org.emrtd-tester.evidence.1" to stringResource(R.string.namespace_how_verified),
    "org.iso.23220.1" to stringResource(R.string.namespace_identity),
    "org.iso.23220.photoID.1" to stringResource(R.string.namespace_photo_id),
    "org.iso.23220.dtc.1" to stringResource(R.string.namespace_dtc),
    "eu.europa.ec.av.1" to stringResource(R.string.namespace_age),
)

@Composable
fun claimLabel(name: String): String {
    val over = Regex("age_over_(\\d+)").matchEntire(name)
    return claimLabelsMap()[name]
        ?: over?.let { stringResource(R.string.claim_over_n, it.groupValues[1]) }
        ?: name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun formatValue(v: Any?): String = when (v) {
    null -> "—"
    is ByteArray -> stringResource(R.string.value_bytes, v.size)
    is Boolean -> stringResource(if (v) R.string.value_yes else R.string.value_no)
    is Map<*, *> -> v.entries.map { "${it.key}: ${formatValue(it.value)}" }.joinToString(", ")
    is Collection<*> -> v.map { formatValue(it) }.joinToString(", ")
    is String -> if (v.length > 120 && v.none { it == ' ' }) stringResource(R.string.value_bytes_binary, v.length * 3 / 4) else v
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
                Image(painterResource(R.drawable.ic_brand_emblem), null, Modifier.size(32.dp))
                Column(Modifier.padding(start = 10.dp)) {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.brand_tagline), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.settings_how_it_works)) }
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == HomeTab.WALLET, { tab = HomeTab.WALLET },
                    icon = { Icon(Icons.Filled.Wallet, null) }, label = { Text(stringResource(R.string.nav_wallet)) })
                NavigationBarItem(false, onScan, icon = { Icon(Icons.Filled.QrCodeScanner, null) }, label = { Text(stringResource(R.string.nav_scan)) })
                NavigationBarItem(false, { if (docs.isEmpty()) noDocs = true else onShowQr() },
                    icon = { Icon(Icons.Filled.QrCode, null) }, label = { Text(stringResource(R.string.nav_show_qr)) })
                NavigationBarItem(tab == HomeTab.SETTINGS, { tab = HomeTab.SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.nav_settings)) })
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
        title = { Text(stringResource(R.string.dialog_nothing_to_share_title)) },
        text = { Text(stringResource(R.string.dialog_nothing_to_share_body)) },
        confirmButton = { TextButton(onClick = { noDocs = false; onAdd() }) { Text(stringResource(R.string.action_add_pid)) } },
        dismissButton = { TextButton(onClick = { noDocs = false }) { Text(stringResource(R.string.action_later)) } },
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
                Text(
                    if (holder != null) stringResource(R.string.home_greeting_hello,
                        holder.substringBefore(' ').lowercase().replaceFirstChar { it.uppercase() })
                    else stringResource(R.string.home_greeting_welcome),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                )
                Text(
                    if (docs.isEmpty()) stringResource(R.string.home_subtitle_empty)
                    else stringResource(R.string.home_subtitle_count, docs.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Filled.QrCodeScanner, stringResource(R.string.quick_scan_title),
                    stringResource(R.string.quick_scan_subtitle), Modifier.weight(1f), onScan)
                QuickAction(Icons.Filled.QrCode, stringResource(R.string.quick_show_qr_title),
                    stringResource(R.string.quick_show_qr_subtitle), Modifier.weight(1f), onShowQr, enabled = docs.isNotEmpty())
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
                        Text(stringResource(R.string.add_document_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.add_document_subtitle), style = MaterialTheme.typography.bodySmall)
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
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.Blue), contentAlignment = Alignment.Center) {
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
            BrandLockup(emblemSize = 48.dp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.wallet_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.wallet_empty_body),
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
                painterResource(R.drawable.ic_brand_emblem), null,
                Modifier.size(170.dp).align(Alignment.CenterEnd).offset(x = 50.dp).alpha(0.14f),
            )
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.ic_brand_emblem), null, Modifier.size(26.dp))
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        Text(docTypeName(doc.docType, LocalContext.current), color = Color.White, style = MaterialTheme.typography.labelLarge,
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
                            Text(if (over != null) stringResource(R.string.doc_over_label, over) else stringResource(R.string.doc_age_verified),
                                color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.doc_no_name_photo), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(name ?: doc.name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            doc.firstClaimText("birth_date")?.let {
                                Text(stringResource(R.string.doc_born, it), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(stringResource(R.string.doc_valid_until, doc.expiryText()), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun IssuedDocument.firstClaimText(name: String): String? = firstClaim(name)?.let { formatValue(it) }

@Composable
private fun IssuedDocument.issuerLabel(): String {
    val ev = claims()["org.emrtd-tester.evidence.1"]?.get("evidence_type")?.toString()
    val source = when (ev) {
        "emrtd_chip" -> stringResource(R.string.issuer_source_chip)
        "document_image" -> stringResource(R.string.issuer_source_scan)
        "manual_entry_unverified" -> stringResource(R.string.issuer_source_test)
        else -> ""
    }
    val brandName = stringResource(R.string.brand_short_name)
    val authority = firstClaim("issuing_authority", "issuing_authority_unicode")?.toString()
    return (authority?.let { if (brandName in it) brandName else it } ?: stringResource(R.string.issuer_label_default)) + source
}

@Composable
fun Portrait(bytes: ByteArray?, sizeDp: Int) {
    val bmp = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.size(width = (sizeDp * 0.8).dp, height = sizeDp.dp), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = stringResource(R.string.content_desc_portrait), contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
        else Icon(Icons.Filled.Badge, null, Modifier.size((sizeDp * 0.6).dp), tint = Brand.Grey)
    }
}

// ---------------------------------------------------------------------------
// Add a document
// ---------------------------------------------------------------------------

@Composable
fun AddPidScreen(vm: MainViewModel, onChip: () -> Unit, onScan: () -> Unit, onQr: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val nfc = rememberNfcStatus()
    Scaffold(topBar = { SimpleTopBar(stringResource(R.string.add_document_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.section_get_your_pid), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OptionCard(
                icon = Icons.Filled.Nfc,
                title = stringResource(R.string.option_chip_title),
                badge = stringResource(R.string.option_chip_badge),
                body = stringResource(when (nfc) {
                    NfcStatus.UNAVAILABLE -> R.string.option_chip_body_unavailable
                    NfcStatus.DISABLED -> R.string.option_chip_body_disabled
                    else -> R.string.option_chip_body_enabled
                }),
                enabled = nfc != NfcStatus.UNAVAILABLE,
                onClick = { if (nfc == NfcStatus.DISABLED) openNfcSettings(context) else onChip() },
            )
            OptionCard(
                icon = Icons.Filled.CameraAlt,
                title = stringResource(R.string.option_scan_title),
                badge = stringResource(R.string.option_scan_badge),
                body = stringResource(R.string.option_scan_body),
                onClick = onScan,
            )
            Text(stringResource(R.string.section_other_credentials), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp))
            OptionCard(
                icon = Icons.Filled.QrCodeScanner,
                title = stringResource(R.string.option_offer_title),
                body = stringResource(R.string.option_offer_body),
                onClick = onQr,
            )
            OptionCard(
                icon = Icons.Filled.Cloud,
                title = stringResource(R.string.issuer_portal_menu_title),
                body = stringResource(R.string.option_portal_body),
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
                            modifier = Modifier.padding(start = 8.dp).background(Brand.Red, RoundedCornerShape(6.dp))
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
        SimpleTopBar(doc?.let { docTypeName(it.docType, LocalContext.current) } ?: stringResource(R.string.document_title_fallback), onBack) {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
        }
    }) { padding ->
        if (doc == null) {
            Text(stringResource(R.string.document_not_found), Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }
        val claims = remember(doc.id) { doc.claims() }
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DocumentCard(doc) {}
            OutlinedButton(onClick = onShowQr, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.QrCode, null)
                Text(stringResource(R.string.action_show_qr_in_person), Modifier.padding(start = 8.dp))
            }
            val fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault())
            Text(stringResource(R.string.document_added_on, fmt.format(doc.issuedAt), doc.docType), style = MaterialTheme.typography.labelSmall)
            val namespaceLabels = namespaceLabelsMap()
            claims.forEach { (ns, elements) ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(namespaceLabels[ns] ?: ns, style = MaterialTheme.typography.titleSmall,
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
            val deleteReason = stringResource(R.string.auth_reason_delete_credential)
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(R.string.dialog_delete_credential_title)) },
                text = { Text(stringResource(R.string.dialog_delete_credential_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        auth.request(deleteReason) { vm.repository.delete(doc.id); onBack() }
                    }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
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
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SettingsGroup(stringResource(R.string.settings_group_activity)) {
            SettingsRow(Icons.Filled.History, stringResource(R.string.settings_activity_row_title),
                if (entries.isEmpty()) stringResource(R.string.settings_activity_none)
                else stringResource(R.string.settings_activity_summary, entries.size, entries.first().title),
                onClick = onActivity)
        }
        SettingsGroup(stringResource(R.string.settings_group_security)) {
            SettingsRow(Icons.Filled.Lock, stringResource(R.string.settings_unlock_method), stringResource(when (method) {
                LockMethod.PIN -> R.string.settings_unlock_pin
                LockMethod.BIOMETRIC -> R.string.settings_unlock_biometric
                LockMethod.NONE -> R.string.settings_unlock_none
            }), onClick = onChangeSecurity)
            LanguageRow()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 40.dp)) {
                    Text(stringResource(R.string.settings_confirm_before_sharing))
                    Text(stringResource(R.string.settings_confirm_before_sharing_body), style = MaterialTheme.typography.bodySmall)
                }
                Switch(authShare, { authShare = it; vm.lock.requireAuthToShare = it })
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_issuer)) {
            SettingsRow(Icons.Filled.Cloud, stringResource(R.string.settings_issuer_backend), vm.settings.issuerUrl, onClick = { editUrl = true })
            SettingsRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.settings_issuer_portal_row), "${vm.settings.issuerUrl}/issuer",
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.settings.issuerUrl + "/issuer"))) })
            SettingsRow(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.settings_issuer_iaca_row), "${vm.settings.issuerUrl}/pki/iaca.pem",
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.settings.issuerUrl + "/pki/iaca.pem"))) })
        }
        SettingsGroup(stringResource(R.string.settings_group_about)) {
            SettingsRow(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.settings_how_it_works), stringResource(R.string.settings_replay_tutorial), onClick = onHelp)
            SettingsRow(Icons.Filled.Info, stringResource(R.string.settings_about_wallet_version, stringResource(R.string.app_name), BuildConfig.VERSION_NAME),
                stringResource(R.string.settings_about_wallet_detail, vm.lock.walletUnitId.take(8)), onClick = null)
        }
    }
    if (editUrl) {
        var url by remember { mutableStateOf(vm.settings.issuerUrl) }
        AlertDialog(
            onDismissRequest = { editUrl = false },
            title = { Text(stringResource(R.string.dialog_issuer_backend_title)) },
            text = { OutlinedTextField(url, { url = it }, singleLine = true, label = { Text(stringResource(R.string.label_url)) }) },
            confirmButton = { TextButton(onClick = { vm.settings.issuerUrl = url; editUrl = false }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { editUrl = false }) { Text(stringResource(R.string.action_cancel)) } },
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

/** English / French (or "Match device") per-app language, applied immediately via AppCompatDelegate. */
@Composable
private fun LanguageRow() {
    var showPicker by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf(currentAppLanguageTag()) }
    SettingsRow(Icons.Filled.Language, stringResource(R.string.settings_language), languageLabel(current), onClick = { showPicker = true })
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    listOf(null, "en", "fr").forEach { tag ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                setAppLanguage(tag)
                                current = tag
                                showPicker = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current == tag, onClick = null)
                            Text(languageLabel(tag), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun languageLabel(tag: String?): String = when (tag) {
    "en" -> stringResource(R.string.settings_language_english)
    "fr" -> stringResource(R.string.settings_language_french)
    else -> stringResource(R.string.settings_language_system)
}
