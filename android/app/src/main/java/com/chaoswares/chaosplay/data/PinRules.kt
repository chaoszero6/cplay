package com.chaoswares.chaosplay.data

import java.security.MessageDigest

/**
 * Privacy folder PIN, spec 7.5: "Privacy folder: hide videos behind a PIN".
 * Only a salted SHA-256 hash is stored, never the PIN itself.
 */
object PinRules {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8

    /** Wrong attempts allowed before a cool-down. */
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_MS = 30_000L

    fun isValid(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Stored form: "salt$hash". */
    fun encode(pin: String, salt: String): String = "$salt$" + hash(pin, salt)

    fun verify(pin: String, stored: String): Boolean {
        if (!isValid(pin)) return false
        val salt = stored.substringBefore('$', "")
        val expected = stored.substringAfter('$', "")
        if (salt.isEmpty() || expected.isEmpty()) return false
        // Constant-time compare so a wrong PIN cannot be timed digit by digit
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), expected.toByteArray())
    }

    /** Remaining lock-out given failed attempts and when the last one failed. */
    fun lockoutRemainingMs(failedAttempts: Int, lastFailureAtMs: Long, nowMs: Long): Long {
        if (failedAttempts < MAX_ATTEMPTS) return 0L
        return (lastFailureAtMs + LOCKOUT_MS - nowMs).coerceAtLeast(0L)
    }
}

/**
 * Rename validation for the library "Rename" action. The extension is
 * kept unless the user typed one, so "Holiday" stays playable as
 * "Holiday.mp4".
 */
object FileNameRules {

    private val FORBIDDEN = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')
    const val MAX_LENGTH = 200

    sealed class Result {
        data class Ok(val fileName: String) : Result()
        data class Error(val message: String) : Result()
    }

    fun rename(original: String, input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Error("Name cannot be empty")
        if (trimmed == "." || trimmed == "..") return Result.Error("That name is reserved")
        if (trimmed.any { it in FORBIDDEN }) return Result.Error("Names cannot contain / \\ : * ? \" < > |")
        if (trimmed.startsWith(".")) return Result.Error("Names cannot start with a dot")
        val originalExt = original.substringAfterLast('.', "")
        val typedExt = trimmed.substringAfterLast('.', "")
        val withExt = if (originalExt.isNotEmpty() && !typedExt.equals(originalExt, ignoreCase = true)) {
            "$trimmed.$originalExt"
        } else trimmed
        if (withExt.length > MAX_LENGTH) return Result.Error("Name is too long")
        if (withExt == original) return Result.Error("That is already the name")
        return Result.Ok(withExt)
    }

    /** Name without extension, prefilled in the rename field. */
    fun stem(fileName: String): String = fileName.substringBeforeLast('.', fileName)
}
