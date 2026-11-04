package com.sentinel.eye.core

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** قفل التطبيق برمز من 6 أرقام مع بصمة اختيارية. الرمز يُخزَّن مُلحَّماً ومُجزَّأً فقط. */
object AppLock {
    const val PIN_LENGTH = 6

    private val st = MutableStateFlow(false)
    val locked: StateFlow<Boolean> get() = st

    @Volatile private var lastStopMs = 0L
    private var fails = 0
    private var blockedUntil = 0L

    fun setPin(pin: String) {
        val salt = PinHash.newSalt()
        val h = PinHash.hash(pin, salt)
        ConfigStore.update { it.copy(lockEnabled = true, lockSalt = salt, lockHash = h) }
        fails = 0
        blockedUntil = 0
    }

    fun remainingBlockSec(): Int {
        val left = blockedUntil - SystemClock.elapsedRealtime()
        return if (left > 0) ((left + 999) / 1000).toInt() else 0
    }

    fun verify(pin: String): Boolean {
        if (remainingBlockSec() > 0) return false
        val c = ConfigStore.current
        if (c.lockHash.isEmpty()) return true
        val ok = PinHash.constantTimeEquals(PinHash.hash(pin, c.lockSalt), c.lockHash)
        if (ok) {
            fails = 0
        } else {
            fails++
            if (fails % 5 == 0) blockedUntil = SystemClock.elapsedRealtime() + 30_000L * (fails / 5)
        }
        return ok
    }

    fun disable() {
        ConfigStore.update { it.copy(lockEnabled = false, lockHash = "", lockSalt = "") }
        st.value = false
    }

    fun onStart() {
        val c = ConfigStore.current
        if (!c.lockEnabled || c.lockHash.isEmpty()) {
            st.value = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastStopMs == 0L || now - lastStopMs >= c.lockTimeout * 1000L) st.value = true
    }

    fun onStop() {
        lastStopMs = SystemClock.elapsedRealtime()
    }

    fun unlock() {
        st.value = false
    }
}

object BiometricHelper {
    fun available(ctx: Context): Boolean = try {
        val bm = ctx.getSystemService(BiometricManager::class.java)
        bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    } catch (_: Throwable) {
        false
    }

    fun prompt(activity: Activity, onSuccess: () -> Unit) {
        try {
            val exec = activity.mainExecutor
            val bp = BiometricPrompt.Builder(activity)
                .setTitle("فتح الحارس الذكي")
                .setSubtitle("استخدم بصمتك")
                .setNegativeButton("استخدام الرمز", exec, DialogInterface.OnClickListener { _, _ -> })
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            bp.authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onSuccess()
                }
            })
        } catch (t: Throwable) {
            AppLog.w("Lock", "تعذّر عرض مطالبة البصمة", t)
        }
    }
}
