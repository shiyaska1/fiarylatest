package com.billing.pos.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Salted SHA-256 password hashing. Stored form: "saltHex:hashHex".
 * Adequate for a local, offline app (no network transmission of passwords).
 */
object PasswordHasher {

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = sha256(salt, password)
        return salt.toHex() + ":" + hash.toHex()
    }

    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].fromHex() ?: return false
        val expected = parts[1]
        return sha256(salt, password).toHex().equals(expected, ignoreCase = true)
    }

    private fun sha256(salt: ByteArray, password: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(password.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
