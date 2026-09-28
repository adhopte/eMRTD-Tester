package io.github.adhopte.emrtdwallet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.data.ReportSection

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF9DB8FF),
            onPrimary = Color(0xFF00205E),
            primaryContainer = Brand.MediumBlue,
            onPrimaryContainer = Color.White,
            secondary = Brand.SkyBlue,
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
            primary = Brand.Blue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE6F7),
            onPrimaryContainer = Brand.Navy,
            secondary = Brand.SkyBlue,
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFDCF1FC),
            onSecondaryContainer = Brand.Navy,
            tertiary = Brand.Red,
            error = Brand.Red,
            background = Brand.Surface,
            onBackground = Brand.Navy,
            surface = Brand.Surface,
            onSurface = Color(0xFF111A3A),
            surfaceVariant = Brand.LightGrey,
            outline = Brand.Grey,
            surfaceContainer = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainerHigh = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * Per-app language (English / French / "match device"), applied immediately without restarting
 * the app. Persisted by AndroidX itself (a system service on API 33+, its own store below that),
 * so nothing needs to be saved in [io.github.adhopte.emrtdwallet.data.AppSettings].
 */
fun currentAppLanguageTag(): String? =
    androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().takeIf { !it.isEmpty }?.get(0)?.language

fun setAppLanguage(tag: String?) {
    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
        if (tag == null) androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        else androidx.core.os.LocaleListCompat.forLanguageTags(tag),
    )
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
    front: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    DisposableEffect(analyzer, imageCapture, front) {
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
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching { provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray()) }
        }, executor)
        onDispose { runCatching { providerFuture.get().unbindAll() } }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * The brand lockup (emblem + name) shown on the tutorial and the empty wallet screen. Built from
 * the flavor's emblem drawable plus a localized name, instead of a baked wordmark image, so it
 * works for any brand/locale without hand-vectorising a logotype.
 */
@Composable
fun BrandLockup(modifier: Modifier = Modifier, emblemSize: Dp = 40.dp, textStyle: TextStyle = MaterialTheme.typography.titleLarge) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_brand_emblem), null, Modifier.size(emblemSize))
        Text(
            stringResource(R.string.app_name),
            modifier = Modifier.padding(start = 10.dp),
            style = textStyle,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun StatusIcon(status: String) {
    val (icon, tint) = when (status) {
        "pass" -> Icons.Filled.CheckCircle to Color(0xFF2E7D32)
        "warn" -> Icons.Filled.Warning to Color(0xFFF9A825)
        "fail" -> Icons.Filled.Error to Brand.Red
        else -> Icons.Filled.RemoveCircleOutline to Color.Gray
    }
    Icon(icon, contentDescription = status, tint = tint, modifier = Modifier.size(20.dp))
}

@Composable
private fun sectionTitle(name: String): String = when (name) {
    "document_data" -> stringResource(R.string.section_document_data)
    "passive_authentication" -> stringResource(R.string.section_passive_authentication)
    "active_authentication" -> stringResource(R.string.section_active_authentication)
    "chip_authentication" -> stringResource(R.string.section_chip_authentication)
    "image_quality" -> stringResource(R.string.section_image_quality)
    "document_authenticity_heuristics" -> stringResource(R.string.section_authenticity_checks)
    "document_content" -> stringResource(R.string.section_mrz_viz_content)
    "biometrics" -> stringResource(R.string.section_biometrics)
    else -> name
}

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
                            sectionTitle(section.name),
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(stringResource(if (expanded) R.string.action_hide else R.string.action_details))
                        }
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
