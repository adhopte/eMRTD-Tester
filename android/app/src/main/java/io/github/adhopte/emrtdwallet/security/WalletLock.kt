package io.github.adhopte.emrtdwallet.security

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class LockMethod { NONE, PIN, BIOMETRIC }

/**
 * Wallet unit protection chosen at initialisation: a 6-digit wallet PIN (stored only as a salted
 * PBKDF2 hash) or the phone's own biometrics / screen lock through BiometricPrompt.
 *
 * The wallet locks on start and after [AUTO_LOCK_MS] in the background, and asks again before
 * any credential is shared.
 */
class WalletLock(context: Context) {
    private val prefs = context.getSharedPreferences("wallet_lock", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext

    private val _locked = MutableStateFlow(method != LockMethod.NONE)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _methodFlow = MutableStateFlow(method)
    val methodFlow: StateFlow<LockMethod> = _methodFlow.asStateFlow()

    private var backgroundedAt = 0L

    val method: LockMethod
        get() = runCatching { LockMethod.valueOf(prefs.getString(KEY_METHOD, null) ?: "NONE") }
            .getOrDefault(LockMethod.NONE)

    val initialized: Boolean get() = method != LockMethod.NONE

    /** Identifier of this wallet unit, created at initialisation. */
    val walletUnitId: String
        get() = prefs.getString(KEY_UNIT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_UNIT_ID, it).apply()
        }

    var requireAuthToShare: Boolean
        get() = prefs.getBoolean(KEY_AUTH_SHARE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTH_SHARE, v).apply()

    // ------------------------------------------------------------------ setup

    fun setPin(pin: String) {
        require(pin.length == PIN_LENGTH && pin.all(Char::isDigit)) { "PIN must be $PIN_LENGTH digits" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash(pin, salt).toHex())
            .putString(KEY_METHOD, LockMethod.PIN.name)
            .putInt(KEY_FAILS, 0)
            .apply()
        _methodFlow.value = LockMethod.PIN
        _locked.value = false
    }

    fun setBiometric() {
        prefs.edit().putString(KEY_METHOD, LockMethod.BIOMETRIC.name).remove(KEY_HASH).remove(KEY_SALT).apply()
        _methodFlow.value = LockMethod.BIOMETRIC
        _locked.value = false
    }

    // ------------------------------------------------------------------ PIN

    /** Seconds until another PIN attempt is allowed (after repeated failures), 0 if allowed now. */
    fun pinCooldownSeconds(): Int {
        val until = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        val now = System.currentTimeMillis()
        return if (until > now) ((until - now + 999) / 1000).toInt() else 0
    }

    fun checkPin(pin: String): Boolean {
        if (pinCooldownSeconds() > 0) return false
        val salt = prefs.getString(KEY_SALT, null)?.fromHex() ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.fromHex() ?: return false
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        val fails = if (ok) 0 else prefs.getInt(KEY_FAILS, 0) + 1
        prefs.edit().putInt(KEY_FAILS, fails).apply {
            // 5 wrong PINs: 30 s pause, doubling for each further miss
            if (fails >= 5) putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + 30_000L * (1L shl (fails - 5).coerceAtMost(6)))
        }.apply()
        return ok
    }

    fun failedAttempts(): Int = prefs.getInt(KEY_FAILS, 0)

    // ------------------------------------------------------------------ lock state

    fun unlock() { _locked.value = false }

    fun onBackground() { backgroundedAt = SystemClock.elapsedRealtime() }

    fun onForeground() {
        if (initialized && backgroundedAt != 0L && SystemClock.elapsedRealtime() - backgroundedAt > AUTO_LOCK_MS) {
            _locked.value = true
        }
        backgroundedAt = 0L
    }

    // ------------------------------------------------------------------ biometrics

    fun biometricAvailable(): Boolean =
        BiometricManager.from(appContext).canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS

    /** Show the system biometric / screen-lock prompt; [onResult] gets null on success or an error message. */
    fun promptBiometric(activity: FragmentActivity, title: String, subtitle: String, onResult: (String?) -> Unit) {
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(null)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(errString.toString())
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators())
            .build()
        prompt.authenticate(info)
    }

    private fun authenticators(): Int =
        // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is only supported from Android 11
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        const val PIN_LENGTH = 6
        const val AUTO_LOCK_MS = 60_000L
        private const val KEY_METHOD = "method"
        private const val KEY_HASH = "pin_hash"
        private const val KEY_SALT = "pin_salt"
        private const val KEY_FAILS = "pin_fails"
        private const val KEY_LOCKOUT_UNTIL = "pin_lockout_until"
        private const val KEY_UNIT_ID = "wallet_unit_id"
        private const val KEY_AUTH_SHARE = "auth_before_share"
    }
}
