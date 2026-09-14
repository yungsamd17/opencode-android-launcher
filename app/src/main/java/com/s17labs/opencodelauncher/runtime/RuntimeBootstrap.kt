package com.s17labs.opencodelauncher.runtime

import android.os.Build

/**
 * Phase 0/1: bootstrap state machine.
 *
 * Phase 1 will port the relevant slice of termux-app's TermuxInstaller.java
 * (bootstrap zip download, checksum verification, extraction into
 * filesDir/proot-env/) plus the Alpine plugin logic from proot-distro for
 * rootfs URLs + checksums per architecture.
 *
 * See NOTICES.md for GPLv3 attribution requirements when that port lands.
 */
sealed interface BootstrapState {
    data object NotStarted : BootstrapState
    data class InProgress(val stepLabel: String, val progress: Float? = null) : BootstrapState
    data object Ready : BootstrapState
    data class Failed(val message: String, val retryable: Boolean = true) : BootstrapState
}

object RuntimeBootstrap {

    /** Supported ABIs. Fail with a clear error on unsupported ones — never silently default. */
    enum class Abi(val rootfsArch: String) {
        ARM64("aarch64"),
        X86_64("x86_64")
    }

    fun detectAbi(): Result<Abi> {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        return when {
            abis.contains("arm64-v8a") -> Result.success(Abi.ARM64)
            abis.contains("x86_64") -> Result.success(Abi.X86_64)
            else -> Result.failure(
                IllegalStateException("Unsupported ABI: ${abis.firstOrNull() ?: "unknown"}. This app needs arm64-v8a or x86_64.")
            )
        }
    }

    fun isBootstrapDone(filesDir: java.io.File): Boolean {
        // Phase 1 will replace this with a real marker (e.g. rootfs + proot binary present).
        return java.io.File(filesDir, "proot-env/.bootstrap-done").exists()
    }
}
