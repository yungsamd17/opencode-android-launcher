package com.s17labs.opencodelauncher.runtime

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object Sha256 {
    fun ofFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Parse first hex token from a .sha256 sidecar body (handles "hash  filename" and bare hash). */
    fun parseSidecar(body: String): String? {
        val token = body.trim().split(Regex("\\s+")).firstOrNull().orEmpty().lowercase()
        return if (token.matches(Regex("[0-9a-f]{64}"))) token else null
    }

    fun verify(file: File, expectedHex: String): Boolean {
        return try {
            ofFile(file).equals(expectedHex.trim().lowercase(), ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}
