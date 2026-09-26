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

/** IN Groupe brand palette (from the CSS custom properties and logo on ingroupe.com). */
object InGroupe {
    val Blue = Color(0xFF002F87)        // logo blue
    val Navy = Color(0xFF192C70)        // --brand-blue
    val MediumBlue = Color(0xFF0D3E96)  // --brand-medium-blue
    val SkyBlue = Color(0xFF43B2ED)     // accent
    val Red = Color(0xFFEA0029)         // logo red
    val DarkRed = Color(0xFF9C2539)     // --brand-red
    val Grey = Color(0xFFAEBBCE)        // --brand-grey
    val LightGrey = Color(0xFFECEFF3)   // --brand-grey-2
    val Surface = Color(0xFFF7F9FB)     // --brand-light-grey
    val CardGradient = listOf(Navy, MediumBlue)
}

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF9DB8FF),
            onPrimary = Color(0xFF00205E),
            primaryContainer = InGroupe.MediumBlue,
            onPrimaryContainer = Color.White,
            secondary = InGroupe.SkyBlue,
            onSecondary = Color(0xFF00344F),
            tertiary = Color(0xFFFF8A9A),
            error = Color(0xFFFF8A9A),
            background = Color(0xFF0B1330),
            surface = Color(0xFF0B1330),
            surfaceVariant = Color(0xFF1E2A55),
            surfaceContainer = Color(0xFF141E42),
            surfaceContainerHigh = Color(0xFF1B2650),
        )
    } else {
        lightColorScheme(
            primary = InGroupe.Blue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE6F7),
            onPrimaryContainer = InGroupe.Navy,
            secondary = InGroupe.SkyBlue,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCF1FC),
            onSecondaryContainer = InGroupe.Navy,
            tertiary = InGroupe.Red,
            error = InGroupe.Red,
            background = InGroupe.Surface,
            onBackground = InGroupe.Navy,
            surface = InGroupe.Surface,
            onSurface = Color(0xFF111A3A),
            surfaceVariant = InGroupe.LightGrey,
            outline = InGroupe.Grey,
            surfaceContainer = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainerHigh = Color.White,
        )
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
        "fail" -> Icons.Filled.Error to InGroupe.Red
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
