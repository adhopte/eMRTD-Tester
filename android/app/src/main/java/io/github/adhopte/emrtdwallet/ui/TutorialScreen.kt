package io.github.adhopte.emrtdwallet.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.adhopte.emrtdwallet.R
import kotlinx.coroutines.launch

private data class TutorialPage(
    val title: String,
    val body: String,
    val illustration: @Composable () -> Unit,
)

private val pages = listOf(
    TutorialPage(
        "Welcome to getYourID Wallet",
        "Turn your passport or ID card into a digital European identity (PID), issued by IN Groupe and " +
            "kept securely on this phone.",
    ) { WelcomeIllustration() },
    TutorialPage(
        "1 · Scan the MRZ",
        "Chip documents: point the camera at the two or three lines of <<< characters at the bottom of the " +
            "passport photo page or on the back of the ID card. They are read automatically.",
    ) { MrzScanIllustration() },
    TutorialPage(
        "2 · Hold the chip to your phone",
        "Place the phone flat on the passport (or the ID card) and keep still. The chip is read over NFC and " +
            "checked by IN Groupe — Passive, Active and Chip Authentication.",
    ) { NfcIllustration() },
    TutorialPage(
        "No chip or no NFC? Auto scan",
        "Choose \"Scan document\". Hold the ID card front, then the back, inside the frame — each side is " +
            "captured automatically when it is sharp and steady. You can also upload a photo or PDF.",
    ) { CardAutoScanIllustration() },
    TutorialPage(
        "Share only what is needed",
        "Show a QR code to an in-person verifier, or open a web verifier's link. You always see who is " +
            "asking and approve exactly which details are shared.",
    ) { ShareIllustration() },
)

@Composable
fun TutorialScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_ingroupe), "IN Groupe", Modifier.height(36.dp))
            Spacer(Modifier.weight(1f))
            if (!last) TextButton(onClick = onFinish) { Text("Skip") }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(
                Modifier.fillMaxSize().padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(28.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFE8EEFA), Color(0xFFF7F9FB)))),
                    contentAlignment = Alignment.Center,
                ) { page.illustration() }
                Text(
                    page.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    page.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 12.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                pages.indices.forEach { i ->
                    val selected = i == pager.currentPage
                    Box(
                        Modifier.height(8.dp).width(if (selected) 24.dp else 8.dp).clip(CircleShape)
                            .background(if (selected) InGroupe.Red else InGroupe.Grey),
                    )
                }
            }
            Button(onClick = {
                if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }) { Text(if (last) "Get started" else "Next") }
        }
    }
}

// ---------------------------------------------------------------------------
// Animated illustrations (drawn in Compose, no image assets)
// ---------------------------------------------------------------------------

/** 0..1 looping progress used to script each animation. */
@Composable
private fun loop(durationMs: Int): Float {
    val t = rememberInfiniteTransition(label = "loop")
    val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "p")
    return v
}

private fun phase(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)

private fun ease(x: Float) = FastOutSlowInEasing.transform(x)

@Composable
private fun WelcomeIllustration() {
    val t = rememberInfiniteTransition(label = "welcome")
    val pulse by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "ring")
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(260.dp)) {
            val r = size.minDimension / 2
            drawCircle(InGroupe.SkyBlue.copy(alpha = (1f - ring) * 0.5f), radius = r * (0.55f + 0.45f * ring),
                style = Stroke(width = 3.dp.toPx()))
        }
        Image(painterResource(R.drawable.ic_ingroupe_emblem), null, Modifier.size(150.dp).scale(pulse))
    }
}

/** A stylised passport data page / ID card. */
@Composable
private fun DocumentMock(
    modifier: Modifier = Modifier,
    width: Dp = 250.dp,
    showMrz: Boolean = true,
    showPortrait: Boolean = true,
    mrzHighlight: Float = 0f,
    title: String = "PASSPORT",
) {
    Box(
        modifier.width(width).height(width * 0.72f).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFFFDFDFE), Color(0xFFE6F0FB))))
            .border(1.dp, InGroupe.Grey, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(title, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = InGroupe.Navy)
            Row(Modifier.padding(top = 4.dp)) {
                if (showPortrait) {
                    Box(Modifier.size(width * 0.2f, width * 0.25f).clip(RoundedCornerShape(4.dp)).background(Color(0xFFB9C7DE))) {
                        Box(Modifier.size(width * 0.09f).align(Alignment.Center).offset(y = (-6).dp).clip(CircleShape)
                            .background(Color(0xFF8193B3)))
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(0.55f, 0.4f, 0.5f, 0.3f).forEach { f ->
                        Box(Modifier.width(width * f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFC9D3E3)))
                    }
                }
            }
        }
        if (showMrz) {
            val glow = InGroupe.SkyBlue.copy(alpha = 0.35f * mrzHighlight)
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(glow, RoundedCornerShape(4.dp))) {
                listOf("P<UTOERIKSSON<<ANNA<MARIA<<<<<<<", "L898902C36UTO7408122F1204159<<<").forEach {
                    Text(it, fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color(0xFF333A4D), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun PhoneFrame(modifier: Modifier = Modifier, width: Dp = 120.dp, content: @Composable BoxScope.() -> Unit = {}) {
    Box(
        modifier.width(width).height(width * 1.9f).clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF111A3A)).padding(5.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.White), content = content)
    }
}

@Composable
private fun MrzScanIllustration() {
    val p = loop(3200)
    val sweep = phase(p, 0.1f, 0.65f)
    val done = phase(p, 0.65f, 0.75f)
    Box(contentAlignment = Alignment.Center) {
        DocumentMock(mrzHighlight = done)
        // Camera viewfinder focused on the MRZ band
        Canvas(Modifier.width(262.dp).height(192.dp)) {
            val bandTop = size.height * 0.7f
            val bandH = size.height * 0.28f
            val color = if (done > 0f) Color(0xFF2E7D32) else InGroupe.SkyBlue
            drawRoundRect(color, Offset(0f, bandTop), Size(size.width, bandH), CornerRadius(10.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()))
            if (done == 0f && sweep > 0f) {
                val y = bandTop + bandH * sweep
                drawLine(InGroupe.Red, Offset(8.dp.toPx(), y), Offset(size.width - 8.dp.toPx(), y), 3.dp.toPx())
            }
        }
        if (done > 0f) {
            Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32),
                modifier = Modifier.size(44.dp).align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp).scale(done))
        }
    }
}

@Composable
private fun NfcIllustration() {
    val p = loop(3600)
    val approach = ease(phase(p, 0f, 0.35f))
    val reading = phase(p, 0.35f, 0.85f)
    val done = phase(p, 0.85f, 0.9f)
    Box(Modifier.size(300.dp, 280.dp), contentAlignment = Alignment.Center) {
        // Passport cover
        Box(
            Modifier.size(170.dp, 230.dp).offset(x = (-30).dp, y = 10.dp).clip(RoundedCornerShape(10.dp)).background(InGroupe.Navy),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("PASSPORT", color = Color(0xFFE8C66A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Box(Modifier.padding(top = 10.dp).size(28.dp, 18.dp).border(2.dp, Color(0xFFE8C66A), RoundedCornerShape(3.dp)))
            }
        }
        // NFC waves while reading
        if (reading > 0f && done == 0f) {
            Canvas(Modifier.size(260.dp)) {
                repeat(3) { i ->
                    val w = ((reading * 3f + i / 3f) % 1f)
                    drawCircle(InGroupe.SkyBlue.copy(alpha = 1f - w), radius = size.minDimension / 2 * (0.3f + 0.7f * w),
                        style = Stroke(width = 3.dp.toPx()))
                }
            }
        }
        // Phone slides down onto the passport
        PhoneFrame(Modifier.offset(x = 40.dp, y = (-170).dp * (1f - approach) + 70.dp).rotate(-8f).alpha(0.97f), width = 96.dp) {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (done > 0f) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(40.dp))
                    Text("Chip verified", fontSize = 10.sp, color = InGroupe.Navy, textAlign = TextAlign.Center)
                } else {
                    Icon(Icons.Filled.Nfc, null, tint = InGroupe.Blue, modifier = Modifier.size(34.dp))
                    Text(if (reading > 0f) "Reading chip…" else "Hold still", fontSize = 10.sp, color = InGroupe.Navy)
                    LinearProgressIndicator(progress = { reading }, modifier = Modifier.padding(top = 8.dp).width(80.dp),
                        color = InGroupe.Red, trackColor = InGroupe.LightGrey)
                }
            }
        }
    }
}

@Composable
private fun CardAutoScanIllustration() {
    val p = loop(5200)
    // front: slide in 0-0.18, hold 0.18-0.33, captured 0.33-0.45; flip to back 0.45-0.55; back hold 0.55-0.75, captured 0.75-0.9
    val slide = ease(phase(p, 0f, 0.18f))
    val flip = phase(p, 0.45f, 0.55f)
    val back = flip >= 0.5f
    val frontCaptured = p in 0.33f..0.45f
    val backCaptured = p in 0.75f..0.95f
    val holding = (p in 0.18f..0.33f) || (p in 0.55f..0.75f)
    val color = when {
        frontCaptured || backCaptured -> Color(0xFF2E7D32)
        holding -> Color(0xFFF9A825)
        else -> Color.White
    }
    val label = when {
        frontCaptured -> "Front captured"
        backCaptured -> "Back captured"
        holding -> "Hold still…"
        back -> "Now the back"
        else -> "Looking for the card…"
    }
    Box(Modifier.size(300.dp, 280.dp), contentAlignment = Alignment.Center) {
        // Camera preview
        Box(Modifier.size(280.dp, 250.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF2B3350)))
        DocumentMock(
            modifier = Modifier
                .offset(x = 220.dp * (1f - slide))
                .rotate(8f * (1f - slide))
                .graphicsLayer { rotationY = flip * 180f; cameraDistance = 12f * density },
            width = 220.dp,
            showMrz = back,
            showPortrait = !back,
            title = if (back) "" else "IDENTITY CARD",
            mrzHighlight = if (backCaptured) 1f else 0f,
        )
        Canvas(Modifier.size(236.dp, 150.dp)) {
            drawRoundRect(color, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(width = 4.dp.toPx()))
        }
        Text(
            label, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        if (frontCaptured || backCaptured) {
            Box(Modifier.size(280.dp, 250.dp).clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.25f * (1f - phase(p, if (frontCaptured) 0.33f else 0.75f, if (frontCaptured) 0.38f else 0.8f)))))
        }
    }
}

@Composable
private fun ShareIllustration() {
    val p = loop(3000)
    val beam = phase(p, 0.2f, 0.6f)
    val done = phase(p, 0.6f, 0.7f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        PhoneFrame(width = 110.dp) {
            Canvas(Modifier.size(78.dp).align(Alignment.Center)) {
                val n = 9
                val cell = size.width / n
                for (y in 0 until n) for (x in 0 until n) {
                    val finder = (x < 3 && y < 3) || (x >= n - 3 && y < 3) || (x < 3 && y >= n - 3)
                    if (finder || (x * 7 + y * 13 + x * y) % 3 == 0) {
                        drawRect(InGroupe.Navy, Offset(x * cell, y * cell), Size(cell * 0.92f, cell * 0.92f))
                    }
                }
            }
        }
        Canvas(Modifier.size(50.dp, 20.dp)) {
            repeat(4) { i ->
                val a = ((beam * 4f - i).coerceIn(0f, 1f))
                drawCircle(InGroupe.SkyBlue.copy(alpha = a), radius = 4.dp.toPx(), center = Offset(size.width * (i + 0.5f) / 4, size.height / 2))
            }
        }
        Box(
            Modifier.size(110.dp, 150.dp).clip(RoundedCornerShape(16.dp)).background(InGroupe.Navy).padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Verifier", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if (done > 0f) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(40.dp).scale(done))
                    Text("Over 18 ✓", color = Color.White, fontSize = 10.sp)
                } else {
                    Box(Modifier.size(40.dp).border(2.dp, InGroupe.SkyBlue, RoundedCornerShape(8.dp)))
                    Text("Waiting…", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                }
            }
        }
    }
}
