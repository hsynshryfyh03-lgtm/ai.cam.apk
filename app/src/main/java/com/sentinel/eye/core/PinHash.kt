package com.sentinel.eye.core

import java.security.MessageDigest
import java.security.SecureRandom

/** تجزئة الرمز: SHA-256 مكرّرة 2000 مرة مع ملح عشوائي (دون أي اعتماد على أندرويد). */
object PinHash {
    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var d = (salt + ":" + pin).toByteArray(Charsets.UTF_8)
        repeat(2000) { d = md.digest(d) }
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        val sb = StringBuilder()
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
