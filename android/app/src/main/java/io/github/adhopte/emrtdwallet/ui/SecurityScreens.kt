package io.github.adhopte.emrtdwallet.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import io.github.adhopte.emrtdwallet.R
import io.github.adhopte.emrtdwallet.data.ActivityLog
import io.github.adhopte.emrtdwallet.data.ActivityType
import io.github.adhopte.emrtdwallet.security.LockMethod
import io.github.adhopte.emrtdwallet.security.WalletLock
import kotlinx.coroutines.delay

tailrec fun Context.findActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Six PIN dots and a numeric keypad. [onComplete] fires when all digits are entered. */
@Composable
fun PinPad(
    title: String,
    subtitle: String?,
    error: String?,
    onComplete: (String) -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    dark: Boolean = false,
) {
    var pin by remember { mutableStateOf("") }
    val fg = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = fg, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.75f),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
        }
        Row(Modifier.padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(WalletLock.PIN_LENGTH) { i ->
                val filled = i < pin.length
                val s by animateFloatAsState(if (filled) 1.15f else 1f, label = "dot")
                Box(
                    Modifier.size(16.dp).scale(s).clip(CircleShape)
                        .background(if (filled) (if (dark) Color.White else InGroupe.Blue) else Color.Transparent)
                        .border(2.dp, if (dark) Color.White else InGroupe.Blue, CircleShape)
                )
            }
        }
        Text(error ?: " ", color = if (dark) Color(0xFFFFB3BE) else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (k.isEmpty()) Color.Transparent else fg.copy(alpha = 0.08f))
                            .clickable(enabled = k.isNotEmpty()) {
                                if (k == "<") pin = pin.dropLast(1)
                                else if (pin.length < WalletLock.PIN_LENGTH) {
                                    pin += k
                                    if (pin.length == WalletLock.PIN_LENGTH) {
                                        val done = pin
                                        pin = ""
                                        onComplete(done)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            "<" -> Icon(Icons.AutoMirrored.Filled.Backspace, "Delete", tint = fg)
                            "" -> Unit
                            else -> Text(k, fontSize = 28.sp, color = fg, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        extraAction?.invoke()
    }
}

/** Wallet unit initialisation: choose between the phone's biometrics / screen lock and a wallet PIN. */
@Composable
fun SetupSecurityScreen(lock: WalletLock, activity: ActivityLog, changing: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<LockMethod?>(null) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val biometricOk = remember { lock.biometricAvailable() }

    fun finish(method: LockMethod) {
        if (changing) activity.add(ActivityType.SECURITY_CHANGED, "Unlock method changed",
            if (method == LockMethod.PIN) "Wallet PIN" else "Biometrics / device lock")
        else activity.add(ActivityType.WALLET_CREATED, "Wallet initialised",
            "Wallet unit ${lock.walletUnitId.take(8)} · protected by " +
                if (method == LockMethod.PIN) "a wallet PIN" else "biometrics / device lock")
        onDone()
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        when (mode) {
            null -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.height(24.dp))
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(InGroupe.CardGradient)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Shield, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Text(if (changing) "Change how you unlock" else "Protect your wallet",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Your wallet holds your identity. Choose how you unlock it and confirm each time you share " +
                    "data with a relying party.", style = MaterialTheme.typography.bodyMedium)
                SecurityOption(
                    Icons.Filled.Fingerprint, "Biometrics",
                    if (biometricOk) "Fingerprint or face unlock of this phone (falls back to the phone's screen lock)."
                    else "Not available: set up a fingerprint / face unlock or a screen lock on this phone first.",
                    enabled = biometricOk,
                ) {
                    val act = context.findActivity() ?: return@SecurityOption
                    lock.promptBiometric(act, "Enable biometric unlock", "Confirm it's you") { err ->
                        if (err == null) {
                            lock.setBiometric(); finish(LockMethod.BIOMETRIC)
                        } else error = err
                    }
                }
                SecurityOption(Icons.Filled.Pin, "Wallet PIN", "A 6-digit PIN used only by this wallet.") {
                    mode = LockMethod.PIN
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            else -> Column(Modifier.fillMaxSize().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PinPad(
                    title = if (firstPin == null) "Create a wallet PIN" else "Confirm your PIN",
                    subtitle = if (firstPin == null) "Choose 6 digits. Avoid dates and simple sequences." else "Enter the same 6 digits again",
                    error = error,
                    onComplete = { pin ->
                        val first = firstPin
                        when {
                            first == null && isWeak(pin) -> error = "Too easy to guess — choose another PIN"
                            first == null -> { firstPin = pin; error = null }
                            first != pin -> { firstPin = null; error = "PINs did not match — start again" }
                            else -> { lock.setPin(pin); finish(LockMethod.PIN) }
                        }
                    },
                    extraAction = { TextButton(onClick = { mode = null; firstPin = null; error = null }) { Text("Back") } },
                )
            }
        }
    }
}

private fun isWeak(pin: String): Boolean {
    if (pin.toSet().size == 1) return true
    val asc = "0123456789012345"
    val desc = asc.reversed()
    return pin in asc || pin in desc
}

@Composable
private fun SecurityOption(icon: ImageVector, title: String, body: String, enabled: Boolean = true, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Full-screen lock shown on start and after the wallet was in the background. */
@Composable
fun LockScreen(lock: WalletLock) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableIntStateOf(lock.pinCooldownSeconds()) }
    fun biometric() {
        val act = context.findActivity() ?: return
        lock.promptBiometric(act, "Unlock getYourID Wallet", "Confirm it's you") { err ->
            if (err == null) lock.unlock() else error = err
        }
    }
    LaunchedEffect(Unit) { if (lock.method == LockMethod.BIOMETRIC) biometric() }
    LaunchedEffect(cooldown) {
        if (cooldown > 0) { delay(1000); cooldown = lock.pinCooldownSeconds() }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(InGroupe.CardGradient)).statusBarsPadding()
                .navigationBarsPadding().padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.ic_ingroupe_emblem), null, Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            if (lock.method == LockMethod.PIN) {
                PinPad(
                    title = "Enter your wallet PIN",
                    subtitle = if (cooldown > 0) "Too many attempts. Try again in $cooldown s" else null,
                    error = error,
                    dark = true,
                    onComplete = { pin ->
                        if (cooldown > 0) return@PinPad
                        if (lock.checkPin(pin)) lock.unlock()
                        else {
                            cooldown = lock.pinCooldownSeconds()
                            error = "Wrong PIN (${lock.failedAttempts()} failed attempt(s))"
                        }
                    },
                )
            } else {
                Text("getYourID Wallet is locked", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(32.dp))
                IconButton(onClick = { biometric() }, modifier = Modifier.size(96.dp)) {
                    Icon(Icons.Filled.Fingerprint, "Unlock", tint = Color.White, modifier = Modifier.size(72.dp))
                }
                Button(onClick = { biometric() }) { Text("Unlock") }
                error?.let { Text(it, color = Color(0xFFFFB3BE), modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

/** Asks for the wallet PIN or biometrics before a sensitive action (sharing, deleting). */
class Authenticator internal constructor(private val start: (String, () -> Unit) -> Unit) {
    fun request(reason: String, onSuccess: () -> Unit) = start(reason, onSuccess)
}

@Composable
fun rememberAuthenticator(lock: WalletLock, forSharing: Boolean = true): Authenticator {
    val context = LocalContext.current
    var pinRequest by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val auth = remember(lock) {
        Authenticator { reason, onSuccess ->
            when {
                lock.method == LockMethod.NONE || (forSharing && !lock.requireAuthToShare) -> onSuccess()
                lock.method == LockMethod.BIOMETRIC -> {
                    val act = context.findActivity()
                    if (act == null) onSuccess()
                    else lock.promptBiometric(act, reason, "Confirm it's you") { err -> if (err == null) onSuccess() }
                }
                else -> { error = null; pinRequest = reason to onSuccess }
            }
        }
    }
    pinRequest?.let { (reason, onSuccess) ->
        Dialog(onDismissRequest = { pinRequest = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.width(360.dp).padding(16.dp)) {
                Column(Modifier.padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PinPad(
                        title = reason,
                        subtitle = "Enter your wallet PIN",
                        error = error,
                        onComplete = { pin ->
                            if (lock.checkPin(pin)) { pinRequest = null; onSuccess() }
                            else {
                                val wait = lock.pinCooldownSeconds()
                                error = if (wait > 0) "Too many attempts — wait $wait s" else "Wrong PIN"
                            }
                        },
                        extraAction = { TextButton(onClick = { pinRequest = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
    return auth
}
