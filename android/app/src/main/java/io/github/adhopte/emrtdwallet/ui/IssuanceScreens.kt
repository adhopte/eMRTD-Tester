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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Image
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
import androidx.core.content.ContextCompat
import io.github.adhopte.emrtdwallet.emrtd.ChipAccessKey
import io.github.adhopte.emrtdwallet.emrtd.MrzAnalyzer
import io.github.adhopte.emrtdwallet.emrtd.MrzKey

// ---------------------------------------------------------------------------
// Step 1 (chip flow): MRZ scan or manual entry / CAN
// ---------------------------------------------------------------------------

@Composable
fun MrzScreen(vm: MainViewModel, onContinue: () -> Unit, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    Scaffold(topBar = { SimpleTopBar("Unlock the chip", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Scan MRZ") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Enter manually") })
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
        Text("Camera permission is required to scan the MRZ.", Modifier.padding(16.dp))
        return
    }
    val analyzer = remember { MrzAnalyzer { key -> onMrz(key) } }
    Column(Modifier.padding(16.dp)) {
        Text(
            "Point the camera at the two or three lines of <<< characters at the bottom of the passport " +
                "photo page (or the back of the ID card).",
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
        Text("MRZ data (BAC / PACE)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(doc, { doc = it.uppercase() }, label = { Text("Document number") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dob, { dob = it.filter(Char::isDigit).take(6) }, label = { Text("Date of birth (YYMMDD)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exp, { exp = it.filter(Char::isDigit).take(6) }, label = { Text("Date of expiry (YYMMDD)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        val key = MrzKey(doc.trim(), dob, exp)
        Button(onClick = { onMrz(key) }, enabled = key.isComplete, modifier = Modifier.fillMaxWidth()) { Text("Continue with MRZ") }

        Text("— or —", Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
        Text("Card Access Number (PACE, ID cards)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(can, { can = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit CAN printed on the card") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        Button(onClick = { onCan(can) }, enabled = can.length == 6, modifier = Modifier.fillMaxWidth()) { Text("Continue with CAN") }
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
    LaunchedEffect(state) { if (state is IssuanceState.Finished) onDone() }

    Scaffold(topBar = { SimpleTopBar("Read the chip", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                nfc == NfcStatus.UNAVAILABLE -> {
                    Text("This phone has no NFC reader, so the document chip cannot be read.",
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onUseImageScan, modifier = Modifier.fillMaxWidth()) { Text("Scan document images instead") }
                }
                nfc == NfcStatus.DISABLED -> {
                    Text("NFC is switched off.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { openNfcSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Turn on NFC") }
                    OutlinedButton(onClick = onUseImageScan, modifier = Modifier.fillMaxWidth()) {
                        Text("Scan document images instead")
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
                            Text("Hold the document to the phone again to retry.")
                            OutlinedButton(onClick = onUseImageScan) { Text("Can't read the chip? Scan images instead") }
                        }
                        else -> {
                            Text("Hold the phone against the passport photo page or the ID card.",
                                style = MaterialTheme.typography.titleMedium)
                            val k = key
                            Text(
                                when (k) {
                                    is ChipAccessKey.Can -> "Using CAN ••••${k.can.takeLast(2)}"
                                    is ChipAccessKey.FromMrz -> "Document ${k.mrz.documentNumber}" +
                                        if (k.mrz.name.isNotBlank()) " · ${k.mrz.name}" else ""
                                    null -> "No access key — go back and scan the MRZ"
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text("Don't move the document until reading completes (5–20 s).",
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
                error = e.message ?: "Could not read the selected image"
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
                error = e.message ?: "Could not read the selected PDF"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(state) { if (state is IssuanceState.Finished) onDone() }

    Scaffold(topBar = { SimpleTopBar("Scan document", onBack) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(kind == "passport", { kind = "passport"; back = null; combinedSides = false }, label = { Text("Passport") })
                FilterChip(kind == "id_card", { kind = "id_card" }, label = { Text("ID card") })
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
                    when {
                        capturingBack -> "Now the BACK of the card (with the MRZ): photograph it or upload an image."
                        kind == "passport" -> "Photograph the passport photo page — fill the frame, avoid glare, " +
                            "place it on a dark, plain surface — or upload an existing image."
                        else -> "The FRONT of the ID card (with the portrait): photograph it or upload an image."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (cameraAllowed) {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.75f)) { CameraPreview(Modifier.fillMaxSize(), imageCapture = capture) }
                } else {
                    Text("Camera permission not granted — you can still upload images.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (cameraAllowed) {
                        Button(onClick = {
                            error = null
                            capture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bytes = image.toJpegBytes()
                                    image.close()
                                    accept(bytes)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    error = exception.message
                                }
                            })
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PhotoCamera, null)
                            Text(if (capturingBack) " Capture back" else " Capture", maxLines = 1)
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
                        Text(if (capturingBack) " Image (back)" else " Upload image", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { error = null; pdfPicker.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, null)
                        Text(if (capturingBack) " PDF (back)" else " Upload PDF", maxLines = 1)
                    }
                }
                if (needBack && !capturingBack) {
                    Text("ID card PDF: page 1 = front, page 2 = back (a single page with both sides also works).",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (loading) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                front?.let { Thumb(it, "Front") }
                back?.let { Thumb(it, "Back") }
                if (combinedSides) Text("Front + back on one page", style = MaterialTheme.typography.labelSmall)
            }
            if (front != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { front = null; back = null; uploaded = false; combinedSides = false; error = null; vm.resetIssuance() }) { Text("Start over") }
                    Button(onClick = { vm.submitImages(front!!, back, kind, uploaded) }, enabled = ready) { Text("Verify & issue PID") }
                }
            }
        }
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
fun ResultScreen(vm: MainViewModel, onFinish: () -> Unit) {
    val state by vm.issuance.collectAsState()
    Scaffold(topBar = { SimpleTopBar("Verification result") }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val s = state
            if (s !is IssuanceState.Finished) {
                Text("No result")
                Button(onClick = onFinish) { Text("Back to wallet") }
                return@Column
            }
            val resp = s.outcome.response
            val accepted = s.outcome.document != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (accepted) Icons.Filled.CheckCircle else Icons.Filled.Error, null, Modifier.size(40.dp),
                    tint = if (accepted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(if (accepted) "PID issued and stored" else "PID not issued", style = MaterialTheme.typography.titleLarge)
                    resp.score?.let { Text("Document score ${"%.2f".format(it)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            resp.reasons.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
            s.notes.forEach { Text("ℹ $it", style = MaterialTheme.typography.bodySmall) }
            ReportView(resp.report)
            Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text("Back to wallet") }
        }
    }
}
