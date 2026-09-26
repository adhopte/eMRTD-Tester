package io.github.adhopte.emrtdwallet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.adhopte.emrtdwallet.data.ReportSection

private val EuBlue = Color(0xFF003399)
private val EuYellow = Color(0xFFFFCC00)

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF8FA8FF), secondary = EuYellow)
    } else {
        lightColorScheme(primary = EuBlue, secondary = EuYellow)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Requests a runtime permission once and reports whether it is granted. */
@Composable
fun rememberPermission(permission: String): Boolean {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(permission) { if (!granted) launcher.launch(permission) }
    return granted
}

@Composable
fun rememberCameraPermission(): Boolean = rememberPermission(Manifest.permission.CAMERA)

/** CameraX preview bound to the composition lifecycle, with optional analysis / capture use cases. */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    analyzer: ImageAnalysis.Analyzer? = null,
    imageCapture: ImageCapture? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    DisposableEffect(analyzer, imageCapture) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val useCases = buildList {
                add(preview)
                analyzer?.let {
                    add(ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { a -> a.setAnalyzer(executor, it) })
                }
                imageCapture?.let { add(it) }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
        }, executor)
        onDispose { runCatching { providerFuture.get().unbindAll() } }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
fun StatusIcon(status: String) {
    val (icon, tint) = when (status) {
        "pass" -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        "warn" -> Icons.Filled.Warning to Color(0xFFF9A825)
        "fail" -> Icons.Filled.Error to Color(0xFFC62828)
        else -> Icons.Filled.RemoveCircleOutline to Color.Gray
    }
    Icon(icon, contentDescription = status, tint = tint, modifier = Modifier.size(20.dp))
}

private val SECTION_TITLES = mapOf(
    "document_data" to "Document data",
    "passive_authentication" to "Passive Authentication",
    "active_authentication" to "Active Authentication",
    "chip_authentication" to "Chip Authentication",
    "image_quality" to "Image quality",
    "document_authenticity_heuristics" to "Authenticity checks",
    "document_content" to "MRZ / VIZ content",
)

@Composable
fun ReportView(report: List<ReportSection>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        report.forEach { section ->
            var expanded by remember { mutableStateOf(section.status == "fail") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusIcon(section.status)
                        Text(
                            SECTION_TITLES[section.name] ?: section.name,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Details") }
                    }
                    if (expanded) {
                        section.checks.forEach { check ->
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                                StatusIcon(check.status)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(check.name.replace('_', ' '), style = MaterialTheme.typography.labelLarge)
                                    if (check.detail.isNotBlank()) {
                                        Text(check.detail, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
