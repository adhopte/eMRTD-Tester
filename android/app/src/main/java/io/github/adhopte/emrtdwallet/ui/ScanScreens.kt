package io.github.adhopte.emrtdwallet.ui

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.adhopte.emrtdwallet.MainActivity
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.wallet.OfferController
import io.github.adhopte.emrtdwallet.wallet.OfferState

/** What a scanned QR code / pasted link is for. */
sealed interface ScanTarget {
    data class Offer(val uri: String) : ScanTarget
    data class Presentation(val uri: String) : ScanTarget
    data class Unsupported(val text: String) : ScanTarget
}

fun classifyScan(text: String): ScanTarget {
    val t = text.trim()
    if (OfferController.isCredentialOffer(t)) return ScanTarget.Offer(t)
    val scheme = t.substringBefore("://", "").lowercase()
    if (scheme in MainActivity.REMOTE_SCHEMES || t.startsWith("mdoc:")) return ScanTarget.Presentation(t)
    // Web verifiers sometimes encode the request as an https link with request_uri / client_id
    if (t.startsWith("https://") && ("request_uri=" in t || ("client_id=" in t && "response_type=" in t))) {
        return ScanTarget.Presentation(t)
    }
    return ScanTarget.Unsupported(t)
}

private class QrAnalyzer(private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    @Volatile private var done = false

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (done || media == null) { proxy.close(); return }
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                val value = codes.firstNotNullOfOrNull { it.rawValue }
                if (value != null && !done) { done = true; onText(value) }
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun rearm() { done = false }
    fun close() = runCatching { scanner.close() }
}

/**
 * One scanner for everything: a verifier's QR code (OpenID4VP presentation request) or an
 * issuer's QR code (OpenID4VCI credential offer).
 */
@Composable
fun ScanQrScreen(onTarget: (ScanTarget) -> Unit, onBack: () -> Unit) {
    val cameraAllowed = rememberCameraPermission()
    val haptics = LocalHapticFeedback.current
    var unsupported by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf(false) }
    val analyzer = remember {
        QrAnalyzer { text ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            when (val t = classifyScan(text)) {
                is ScanTarget.Unsupported -> unsupported = t.text
                else -> onTarget(t)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { analyzer.close() } }
    val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "line")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraAllowed) CameraPreview(Modifier.fillMaxSize(), analyzer = analyzer)
        Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension * 0.7f
            val tl = Offset((size.width - side) / 2, (size.height - side) / 2.3f)
            val window = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(tl.x, tl.y, tl.x + side, tl.y + side, CornerRadius(28.dp.toPx())))
            }
            clipPath(window, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
            drawRoundRect(Brand.SkyBlue, tl, Size(side, side), CornerRadius(28.dp.toPx()), style = Stroke(4.dp.toPx()))
            val y = tl.y + 16.dp.toPx() + (side - 32.dp.toPx()) * sweep
            drawLine(Brand.Red, Offset(tl.x + 20.dp.toPx(), y), Offset(tl.x + side - 20.dp.toPx(), y), 3.dp.toPx())
        }
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White) }
            Text(stringResource(R.string.scan_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (cameraAllowed) R.string.scan_hint_camera_allowed else R.string.scan_hint_camera_denied),
                color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScanHint(stringResource(R.string.scan_hint_share_website))
                ScanHint(stringResource(R.string.scan_hint_get_credential))
            }
            OutlinedButton(onClick = { paste = true }) {
                Icon(Icons.Filled.ContentPaste, null, tint = Color.White)
                Text(stringResource(R.string.scan_paste_link), Modifier.padding(start = 8.dp), color = Color.White)
            }
        }
    }
    if (paste) PasteLinkDialog(onDismiss = { paste = false }) { text ->
        paste = false
        when (val t = classifyScan(text)) {
            is ScanTarget.Unsupported -> unsupported = t.text
            else -> onTarget(t)
        }
    }
    unsupported?.let { text ->
        AlertDialog(
            onDismissRequest = { unsupported = null; analyzer.rearm() },
            title = { Text(stringResource(R.string.scan_not_wallet_qr_title)) },
            text = { Text(stringResource(R.string.scan_not_wallet_qr_body, text.take(160))) },
            confirmButton = { TextButton(onClick = { unsupported = null; analyzer.rearm() }) { Text(stringResource(R.string.scan_again)) } },
        )
    }
}

@Composable
private fun ScanHint(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun PasteLinkDialog(onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(clipboard.getText()?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_paste_link_title)) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.scan_paste_link_placeholder)) },
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.scan_open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Credential offer: who issues what, transaction code, progress and result. */
@Composable
fun OfferScreen(vm: MainViewModel, onClose: () -> Unit, onOpenWallet: () -> Unit) {
    val state by vm.offers.state.collectAsState()
    var code by remember { mutableStateOf("") }
    Scaffold(topBar = { SimpleTopBar(stringResource(R.string.offer_add_to_wallet_title), { vm.offers.decline(); onClose() }) }) { padding ->
        Column(
            Modifier.padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                OfferState.Idle, OfferState.Resolving -> {
                    Spacer(Modifier.height(48.dp)); CircularProgressIndicator(); Text(stringResource(R.string.offer_reading))
                }
                is OfferState.Ready -> {
                    IssuerHeader(s.issuer)
                    Text(stringResource(R.string.offer_offers_to_add), style = MaterialTheme.typography.bodyMedium)
                    s.items.forEach { item -> OfferedCredentialCard(item.name, item.docType) }
                    s.txCode?.let { tx ->
                        OutlinedTextField(
                            value = code, onValueChange = { v -> code = if (tx.numeric) v.filter(Char::isDigit) else v },
                            label = { Text(tx.description ?: stringResource(R.string.offer_transaction_code)) },
                            supportingText = { Text(stringResource(R.string.offer_tx_code_hint,
                                tx.length?.let { stringResource(R.string.offer_tx_code_length, it) } ?: "")) },
                            keyboardOptions = KeyboardOptions(keyboardType = if (tx.numeric) KeyboardType.NumberPassword else KeyboardType.Password),
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.offers.decline(); onClose() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_decline)) }
                        Button(
                            onClick = { vm.offers.accept(code.ifBlank { null }) },
                            enabled = s.txCode == null || code.length >= (s.txCode.length ?: 1),
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.action_add)) }
                    }
                }
                is OfferState.Issuing -> {
                    Spacer(Modifier.height(48.dp)); CircularProgressIndicator(); Text(s.message, textAlign = TextAlign.Center)
                }
                is OfferState.Done -> {
                    val ok = s.issued.isNotEmpty()
                    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(72.dp),
                        tint = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                    Text(stringResource(if (ok) R.string.offer_added_to_wallet else R.string.offer_nothing_added), style = MaterialTheme.typography.headlineSmall)
                    s.issued.forEach { Text("✓ $it") }
                    s.deferred.forEach { Text(stringResource(R.string.offer_deferred_item, it)) }
                    s.failed.forEach { Text("✗ $it", color = MaterialTheme.colorScheme.error) }
                    Button(onClick = { vm.offers.reset(); onOpenWallet() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.offer_go_to_wallet)) }
                }
                is OfferState.Failed -> {
                    Icon(Icons.Filled.Error, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.offer_could_not_add), style = MaterialTheme.typography.titleLarge)
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = { vm.offers.reset(); onClose() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

@Composable
fun IssuerHeader(name: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Verified, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun OfferedCredentialCard(name: String, docType: String) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.linearGradient(docStyle(docType).gradient))
            .padding(18.dp)) {
            Column {
                Text(docStyle(docType).badge, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
                Text(name, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(docType, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
