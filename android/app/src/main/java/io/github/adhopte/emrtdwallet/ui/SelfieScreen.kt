package io.github.adhopte.emrtdwallet.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.adhopte.emrtdwallet.liveness.LivenessAnalyzer
import io.github.adhopte.emrtdwallet.liveness.LivenessStep
import io.github.adhopte.emrtdwallet.liveness.LivenessUi
import io.github.adhopte.emrtdwallet.liveness.SelfieCapture

/**
 * Selfie with active liveness: the holder blinks and turns their head both ways, then looks at
 * the camera. The issuer matches the selfie against the chip (DG2) or document portrait.
 */
@Composable
fun SelfieScreen(vm: MainViewModel, onSubmitted: () -> Unit, onBack: () -> Unit) {
    val state by vm.issuance.collectAsState()
    var attempt by remember { mutableIntStateOf(0) }
    var ui by remember { mutableStateOf(LivenessUi(LivenessStep.CENTER)) }
    var started by remember { mutableStateOf(false) }
    val cameraAllowed = rememberCameraPermission()

    LaunchedEffect(state) {
        if (state is IssuanceState.Working || state is IssuanceState.Finished || state is IssuanceState.Failed) onSubmitted()
    }

    val analyzer = remember(attempt, started) {
        if (!started) null else LivenessAnalyzer(
            onUi = { ui = it },
            onComplete = { capture: SelfieCapture -> vm.submitWithSelfie(capture) },
        )
    }
    DisposableEffect(analyzer) { onDispose { analyzer?.close() } }

    Scaffold(topBar = { SimpleTopBar("Verify it's you", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!started) {
                Icon(Icons.Filled.Face, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Take a live selfie", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "The issuer compares your face with the portrait on your document (chip or photo) before " +
                        "issuing your PID. You'll be asked to blink and turn your head — this shows it's really " +
                        "you in front of the camera, not a photo.",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                )
                Tips()
                Button(onClick = { started = true }, enabled = cameraAllowed, modifier = Modifier.fillMaxWidth()) {
                    Text("Start")
                }
                if (!cameraAllowed) Text("Camera permission is needed for the selfie.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { vm.submitWithSelfie(null) }) { Text("Skip (issuer may refuse, lower assurance)") }
                return@Column
            }
            val done = ui.step == LivenessStep.DONE
            Text(if (done) "Great — sending to the issuer…" else ui.step.instruction,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            LinearProgressIndicator(progress = { ui.completed / ui.total.toFloat() }, Modifier.fillMaxWidth())
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f)) {
                if (!done) CameraPreview(Modifier.fillMaxSize(), analyzer = analyzer, front = true)
                else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                FaceOval(ui.faceOk, ui.step)
            }
            Text(ui.hint ?: " ", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = { attempt++; ui = LivenessUi(LivenessStep.CENTER) }) { Text("Restart") }
        }
    }
}

@Composable
private fun Tips() {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("Good, even light on your face", "Remove sunglasses, hat or mask", "Hold the phone at eye level")
            .forEach { Row { Text("•  "); Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
}

@Composable
private fun FaceOval(ok: Boolean, step: LivenessStep) {
    val pulse by rememberInfiniteTransition(label = "oval").animateFloat(
        0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val color = if (ok) Color(0xFF2E7D32) else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width * 0.66f
        val h = w * 1.3f
        val topLeft = Offset((size.width - w) / 2, (size.height - h) / 2.4f)
        val oval = Path().apply { addOval(androidx.compose.ui.geometry.Rect(topLeft, Size(w, h))) }
        clipPath(oval, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.55f)) }
        drawOval(color.copy(alpha = if (ok) 1f else pulse), topLeft, Size(w, h), style = Stroke(width = 5.dp.toPx()))
        // arrows hinting the head turn
        if (step == LivenessStep.TURN || step == LivenessStep.TURN_OTHER) {
            val y = topLeft.y + h / 2
            val a = 18.dp.toPx()
            listOf(topLeft.x - 24.dp.toPx() to -1f, topLeft.x + w + 24.dp.toPx() to 1f).forEach { (x, dir) ->
                val p = Path().apply {
                    moveTo(x, y - a); lineTo(x + dir * a, y); lineTo(x, y + a)
                }
                drawPath(p, Color.White.copy(alpha = pulse), style = Stroke(width = 5.dp.toPx()))
            }
        }
    }
}
