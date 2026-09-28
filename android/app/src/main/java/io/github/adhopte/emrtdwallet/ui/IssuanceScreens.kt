package io.github.adhopte.emrtdwallet.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.rememberCoroutineScope
import io.github.adhopte.emrtdwallet.docscan.DocumentOcr
import io.github.adhopte.emrtdwallet.docscan.DocumentPdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.github.adhopte.emrtdwallet.docscan.AutoCaptureAnalyzer
import io.github.adhopte.emrtdwallet.docscan.AutoCaptureStatus
import io.github.adhopte.emrtdwallet.docscan.CaptureTarget
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Image
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.emrtd.ChipAccessKey
import io.github.adhopte.emrtdwallet.emrtd.MrzAnalyzer
import io.github.adhopte.emrtdwallet.emrtd.MrzKey

// ---------------------------------------------------------------------------
// Step 1 (chip flow): MRZ scan or manual entry / CAN
// ---------------------------------------------------------------------------

@Composable
fun MrzScreen(vm: MainViewModel, onContinue: () -> Unit, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(topBar = { SimpleTopBar(stringResource(R.string.mrz_unlock_chip_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.mrz_tab_scan)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.mrz_tab_manual)) })
            }
            when (tab) {
                0 -> MrzCamera(onMrz = { vm.setMrz(it); onContinue() })
                else -> ManualAccessKey(
                    onMrz = { vm.setMrz(it); onContinue() },
                    onCan = { vm.setCan(it); onContinue() },
                )
            }
        }
    }
}

@Composable
private fun MrzCamera(onMrz: (MrzKey) -> Unit) {
    if (!rememberCameraPermission()) {
        Text(stringResource(R.string.mrz_camera_permission_required), Modifier.padding(16.dp))
        return
    }
    val analyzer = remember { MrzAnalyzer { key -> onMrz(key) } }
    Column(Modifier.padding(16.dp)) {
        Text(
            stringResource(R.string.mrz_camera_instructions),
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().aspectRatio(0.75f)) {
            CameraPreview(Modifier.fillMaxSize(), analyzer = analyzer)
        }
    }
}

@Composable
private fun ManualAccessKey(onMrz: (MrzKey) -> Unit, onCan: (String) -> Unit) {
    var doc by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var exp by remember { mutableStateOf("") }
    var can by remember { mutableStateOf("") }
    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.mrz_data_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(doc, { doc = it.uppercase() }, label = { Text(stringResource(R.string.mrz_document_number)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dob, { dob = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.mrz_date_of_birth)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exp, { exp = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.mrz_date_of_expiry)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        val key = MrzKey(doc.trim(), dob, exp)
        Button(onClick = { onMrz(key) }, enabled = key.isComplete, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mrz_continue_with_mrz)) }

        Text(stringResource(R.string.mrz_or_separator), Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        Text(stringResource(R.string.mrz_can_title), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(can, { can = it.filter(Char::isDigit).take(6) }, label = { Text(stringResource(R.string.mrz_can_label)) },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        Button(onClick = { onCan(can) }, enabled = can.length == 6, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mrz_continue_with_can)) }
    }
}

// ---------------------------------------------------------------------------
// Step 2 (chip flow): NFC reading
// ---------------------------------------------------------------------------

@Composable
fun NfcReadScreen(vm: MainViewModel, onDone: () -> Unit, onUseImageScan: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val adapter = remember { NfcAdapter.getDefaultAdapter(context) }
    val nfc = rememberNfcStatus()
    val state by vm.issuance.collectAsState()
    val key by vm.accessKey.collectAsState()

    DisposableEffect(activity, adapter, nfc) {
        if (activity != null && adapter != null && nfc == NfcStatus.ENABLED) {
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            adapter.enableReaderMode(activity, { tag -> vm.onPassportTag(tag) }, flags, null)
        }
        onDispose { if (activity != null) adapter?.disableReaderMode(activity) }
    }
    LaunchedEffect(state) { if (state is IssuanceState.NeedSelfie) onDone() }

    Scaffold(topBar = { SimpleTopBar(stringResource(R.string.nfc_read_chip_title), onBack) }) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                nfc == NfcStatus.UNAVAILABLE -> {
                    Text(stringResource(R.string.nfc_unavailable_body), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onUseImageScan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nfc_scan_images_instead)) }
                }
                nfc == NfcStatus.DISABLED -> {
                    Text(stringResource(R.string.nfc_disabled_body), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { openNfcSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nfc_turn_on)) }
                    OutlinedButton(onClick = onUseImageScan, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.nfc_scan_images_instead))
                    }
                }
                else -> {
                    Icon(Icons.Filled.Nfc, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                    when (val s = state) {
                        is IssuanceState.Working -> {
                            Text(s.message, style = MaterialTheme.typography.titleMedium)
                            if (s.progress != null) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                            else CircularProgressIndicator()
                        }
                        is IssuanceState.Failed -> {
                            Text(s.message, color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.nfc_hold_again_to_retry))
                            OutlinedButton(onClick = onUseImageScan) { Text(stringResource(R.string.nfc_cant_read_scan_instead)) }
                        }
                        else -> {
                            Text(stringResource(R.string.nfc_hold_phone_instructions),
                                style = MaterialTheme.typography.titleMedium)
                            val k = key
                            Text(
                                when (k) {
                                    is ChipAccessKey.Can -> stringResource(R.string.nfc_using_can, k.can.takeLast(2))
                                    is ChipAccessKey.FromMrz -> stringResource(R.string.nfc_document_number, k.mrz.documentNumber) +
                                        if (k.mrz.name.isNotBlank()) " · ${k.mrz.name}" else ""
                                    null -> stringResource(R.string.nfc_no_access_key)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(stringResource(R.string.nfc_dont_move_document),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Alternative flow: document image capture
// ---------------------------------------------------------------------------

@Composable
fun DocumentScanScreen(vm: MainViewModel, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.issuance.collectAsState()
    var kind by remember { mutableStateOf("passport") }
    var front by remember { mutableStateOf<ByteArray?>(null) }
    var back by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val needBack = kind == "id_card"
    val capturingBack = front != null && needBack && back == null
    // A one-page PDF of an ID card usually holds both sides scanned together
    var combinedSides by remember { mutableStateOf(false) }
    val ready = front != null && (!needBack || back != null || combinedSides)
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var uploaded by remember { mutableStateOf(false) }
    val couldNotReadImage = stringResource(R.string.scan_could_not_read_image)
    val couldNotReadPdf = stringResource(R.string.scan_could_not_read_pdf)

    // Assign a captured or uploaded image to the next empty side (front first, then back)
    fun accept(bytes: ByteArray) {
        if (front == null) front = bytes else back = bytes
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            try {
                accept(withContext(Dispatchers.Default) { DocumentOcr.loadImage(context, uri) })
                uploaded = true
            } catch (e: Exception) {
                error = e.message ?: couldNotReadImage
            } finally {
                loading = false
            }
        }
    }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        scope.launch {
            try {
                // Remaining sides to fill: both for a fresh start, one if the front is already set
                val wanted = if (front == null) (if (needBack) 2 else 1) else 1
                val pages = withContext(Dispatchers.IO) { DocumentPdf.renderPages(context, uri, wanted) }
                pages.forEach(::accept)
                uploaded = true
                if (needBack && front != null && back == null && pages.size == 1) combinedSides = true
            } catch (e: Exception) {
                error = e.message ?: couldNotReadPdf
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(state) { if (state is IssuanceState.NeedSelfie) onDone() }

    val haptics = LocalHapticFeedback.current
    var autoCapture by remember { mutableStateOf(true) }
    var autoStatus by remember { mutableStateOf(AutoCaptureStatus.SEARCHING) }
    var capturing by remember { mutableStateOf(false) }

    fun takePicture() {
        if (capturing) return
        capturing = true
        error = null
        capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bytes = image.toJpegBytes()
                image.close()
                capturing = false
                autoStatus = AutoCaptureStatus.SEARCHING
                accept(bytes)
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                error = exception.message
            }
        })
    }

    // ID card front has no MRZ; passport pages and ID card backs are recognised by a valid MRZ
    val target = if (needBack && front == null) CaptureTarget.CARD_FRONT else CaptureTarget.MRZ_PAGE
    val analyzer = remember(target, autoCapture, ready) {
        if (!autoCapture || ready) null else AutoCaptureAnalyzer(
            target,
            onStatus = { autoStatus = it },
            onCapture = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                takePicture()
            },
        )
    }

    Scaffold(topBar = { SimpleTopBar(stringResource(R.string.scan_document_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == "passport", { kind = "passport"; back = null; combinedSides = false }, label = { Text(stringResource(R.string.scan_kind_passport)) })
                FilterChip(kind == "id_card", { kind = "id_card" }, label = { Text(stringResource(R.string.scan_kind_id_card)) })
            }
            when (val s = state) {
                is IssuanceState.Working -> {
                    Text(s.message, style = MaterialTheme.typography.titleMedium)
                    CircularProgressIndicator()
                    return@Column
                }
                is IssuanceState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                else -> Unit
            }
            if (!ready) {
                val cameraAllowed = rememberCameraPermission()
                Text(
                    stringResource(when {
                        capturingBack -> R.string.scan_instructions_back
                        kind == "passport" -> R.string.scan_instructions_passport
                        else -> R.string.scan_instructions_id_front
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (cameraAllowed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = autoCapture, onCheckedChange = { autoCapture = it; autoStatus = AutoCaptureStatus.SEARCHING })
                        Text(stringResource(R.string.scan_auto_capture), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(Modifier.fillMaxWidth().aspectRatio(0.75f)) {
                        CameraPreview(Modifier.fillMaxSize(), analyzer = analyzer, imageCapture = capture)
                        if (autoCapture) AutoCaptureOverlay(autoStatus, target, capturing)
                    }
                } else {
                    Text(stringResource(R.string.scan_camera_permission_not_granted),
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (cameraAllowed) {
                        Button(onClick = { takePicture() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PhotoCamera, null)
                            Text(
                                stringResource(when {
                                    capturing -> R.string.scan_capturing
                                    capturingBack -> R.string.scan_capture_back
                                    else -> R.string.scan_capture
                                }),
                                Modifier.padding(start = 4.dp), maxLines = 1,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            error = null
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Image, null)
                        Text(stringResource(if (capturingBack) R.string.scan_upload_image_back else R.string.scan_upload_image),
                            Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { error = null; pdfPicker.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, null)
                        Text(stringResource(if (capturingBack) R.string.scan_upload_pdf_back else R.string.scan_upload_pdf),
                            Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                }
                if (needBack && !capturingBack) {
                    Text(stringResource(R.string.scan_id_card_pdf_hint),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                front?.let { Thumb(it, stringResource(R.string.scan_front_label)) }
                back?.let { Thumb(it, stringResource(R.string.scan_back_label)) }
                if (combinedSides) Text(stringResource(R.string.scan_front_back_one_page), style = MaterialTheme.typography.labelSmall)
            }
            if (front != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { front = null; back = null; uploaded = false; combinedSides = false; error = null; vm.resetIssuance() }) { Text(stringResource(R.string.scan_start_over)) }
                    Button(onClick = { vm.prepareImages(front!!, back, kind, uploaded) }, enabled = ready) { Text(stringResource(R.string.scan_continue)) }
                }
            }
        }
    }
}

@Composable
private fun AutoCaptureOverlay(status: AutoCaptureStatus, target: CaptureTarget, capturing: Boolean) {
    val (color, messageRes) = when {
        capturing || status == AutoCaptureStatus.CAPTURE -> Color(0xFF2E7D32) to R.string.autocapture_captured
        status == AutoCaptureStatus.HOLD_STILL -> Color(0xFFF9A825) to R.string.autocapture_hold_still
        status == AutoCaptureStatus.TOO_FAR -> Color.White to R.string.autocapture_move_closer
        status == AutoCaptureStatus.WRONG_SIDE -> Color(0xFFC62828) to R.string.autocapture_wrong_side
        target == CaptureTarget.MRZ_PAGE -> Color.White to R.string.autocapture_looking_mrz
        else -> Color.White to R.string.autocapture_looking_card
    }
    val message = stringResource(messageRes)
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        // Guide frame: ID-1 / passport page proportions in landscape within the portrait preview
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.42f).align(Alignment.Center)) {
            drawRoundRect(color = color, style = Stroke(width = 4.dp.toPx()), cornerRadius = CornerRadius(12.dp.toPx()))
        }
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

@Composable
private fun Thumb(jpeg: ByteArray, label: String) {
    val bmp = remember(jpeg) {
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 })
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        bmp?.let { Image(it.asImageBitmap(), label, Modifier.height(96.dp)) }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// ---------------------------------------------------------------------------
// Result
// ---------------------------------------------------------------------------

@Composable
fun ResultScreen(vm: MainViewModel, onFinish: () -> Unit, onOffer: (String) -> Unit, onRetry: () -> Unit) {
    val state by vm.issuance.collectAsState()
    Scaffold(topBar = { SimpleTopBar(stringResource(R.string.result_your_pid_title)) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = state) {
                is IssuanceState.Working -> {
                    Spacer(Modifier.height(64.dp))
                    CircularProgressIndicator(Modifier.size(56.dp))
                    Text(s.message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.result_may_take_a_minute), style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center)
                }
                is IssuanceState.Failed -> {
                    Spacer(Modifier.height(48.dp))
                    Icon(Icons.Filled.Error, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.result_something_went_wrong), style = MaterialTheme.typography.titleLarge)
                    Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_try_again)) }
                    OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_back_to_wallet)) }
                }
                is IssuanceState.Finished -> FinishedResult(s, onFinish, onOffer)
                else -> {
                    Text(stringResource(R.string.result_no_result))
                    Button(onClick = onFinish) { Text(stringResource(R.string.result_back_to_wallet)) }
                }
            }
        }
    }
}

@Composable
private fun FinishedResult(s: IssuanceState.Finished, onFinish: () -> Unit, onOffer: (String) -> Unit) {
    val resp = s.outcome.response
    val doc = s.outcome.document
    if (doc != null) {
        Icon(Icons.Filled.CheckCircle, null, Modifier.size(56.dp), tint = Color(0xFF2E7D32))
        Text(stringResource(R.string.result_pid_in_wallet), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        DocumentCard(doc) {}
        resp.attestation_offer?.takeIf { it.credentials.isNotEmpty() }?.let { offer ->
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.result_also_available), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    offer.credentials.forEach { Text("• ${it.name}") }
                    Button(onClick = { onOffer(offer.uri) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_add_attestations)) }
                }
            }
        }
    } else {
        Icon(Icons.Filled.Error, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.result_pid_not_issued), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        resp.reasons.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
    }
    resp.score?.let { Text(stringResource(R.string.result_document_score, "%.2f".format(it)), style = MaterialTheme.typography.bodySmall) }
    s.notes.forEach { Text("ℹ $it", style = MaterialTheme.typography.bodySmall) }
    Text(stringResource(R.string.result_verification_report), style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    ReportView(resp.report)
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.result_back_to_wallet)) }
}
