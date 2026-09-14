package com.s17labs.opencodelauncher.runtime

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Phase 0/1: bootstrap state machine + Phase 1 downloader/verifier.
 *
 * Flow inspired by termux-app's TermuxInstaller.java (staging dir, checksum
 * verification, atomic rename on success) and proot-distro's Alpine plugin
 * (rootfs URLs + checksums per architecture). No Termux code is copied
 * verbatim here — this is original Kotlin glue following the same steps.
 * If verbatim adaptation lands later, it must carry the GPLv3 origin header
 * and be listed in NOTICES.md.
 *
 * See NOTICES.md for licensing requirements.
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

    const val MAX_DOWNLOAD_ATTEMPTS = 3

    fun detectAbi(): Result<Abi> {
        val abis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        return mapAbis(abis)
    }

    /** Pure function for unit tests — maps an ABI list to our Abi, no device needed. */
    fun mapAbis(abis: List<String>): Result<Abi> {
        return when {
            abis.contains("arm64-v8a") -> Result.success(Abi.ARM64)
            abis.contains("x86_64") -> Result.success(Abi.X86_64)
            else -> Result.failure(
                IllegalStateException("Unsupported ABI: ${abis.firstOrNull() ?: "unknown"}. This app needs arm64-v8a or x86_64.")
            )
        }
    }

    fun envDir(filesDir: File): File = File(filesDir, "proot-env")
    fun rootfsDir(filesDir: File): File = File(envDir(filesDir), "alpine-rootfs")
    fun stagingDir(filesDir: File): File = File(envDir(filesDir), "alpine-rootfs-staging")
    fun markerFile(filesDir: File): File = File(envDir(filesDir), ".bootstrap-done")

    fun isBootstrapDone(filesDir: File): Boolean {
        return markerFile(filesDir).exists() &&
            File(rootfsDir(filesDir), "bin/sh").exists()
    }

    /**
     * Phase 1 proof-of-concept: download Alpine minirootfs, verify SHA-256
     * against the CDN sidecar, extract to staging, atomic rename, write marker.
     * Emits progress via [onState]. Returns final state.
     *
     * Never retries indefinitely — at most [MAX_DOWNLOAD_ATTEMPTS] attempts,
     * then surfaces Failed(retryable=true) for the UI retry action.
     */
    suspend fun bootstrap(
        filesDir: File,
        onState: (BootstrapState) -> Unit = {}
    ): BootstrapState = withContext(Dispatchers.IO) {
        val abiRes = detectAbi()
        if (abiRes.isFailure) {
            val f = BootstrapState.Failed(abiRes.exceptionOrNull()?.message ?: "Unsupported ABI", retryable = false)
            onState(f); return@withContext f
        }
        val abi = abiRes.getOrThrow()

        // Low-storage pre-check (~150-300MB per plan §6): require 600MB free.
        try {
            val free = filesDir.freeSpace
            if (free in 1 until 600L * 1024 * 1024) {
                val f = BootstrapState.Failed(
                    "Low storage: ${free / 1024 / 1024}MB free, need ~600MB for the Alpine rootfs.",
                    retryable = true
                )
                onState(f); return@withContext f
            }
        } catch (_: Exception) { /* best-effort check only */ }

        val env = envDir(filesDir).apply { mkdirs() }
        val tarball = File(env, AlpineCatalog.tarballFileName(abi))
        val rootfs = rootfsDir(filesDir)
        val staging = stagingDir(filesDir)

        // 1. Download (tmp-rename pattern like proot-distro). Skip if a
        // verified tarball is already on disk (retry after extract failure
        // must not re-download a good file on a slow connection).
        var attempt = 0
        var downloaded = tarball.exists() && tarball.length() > 1_000_000
        if (downloaded) onState(BootstrapState.InProgress("Reusing downloaded tarball (${tarball.length() / 1024 / 1024}MB)…"))
        var lastErr = ""
        var lastPct = -1
        while (attempt < MAX_DOWNLOAD_ATTEMPTS && !downloaded) {
            attempt++
            onState(BootstrapState.InProgress("Downloading Alpine $attempt/$MAX_DOWNLOAD_ATTEMPTS…"))
            try {
                downloadUrl(AlpineCatalog.tarballUrl(abi), tarball) { done, total ->
                    val pct = if (total > 0) ((done * 100) / total).toInt() else -1
                    // Throttle: only emit on integer-percent change, or the log
                    // floods (one emit per 32KB chunk) and buries later steps.
                    if (pct != lastPct) {
                        lastPct = pct
                        val p = if (total > 0) done.toFloat() / total else null
                        onState(BootstrapState.InProgress("Downloading Alpine… $pct%", p))
                    }
                }
                downloaded = true
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        if (!downloaded) {
            val f = BootstrapState.Failed("Download failed after $MAX_DOWNLOAD_ATTEMPTS attempts: $lastErr", retryable = true)
            onState(f); return@withContext f
        }

        // 2. Verify SHA-256 against CDN sidecar.
        onState(BootstrapState.InProgress("Verifying checksum…"))
        try {
            val expected = fetchExpectedSha256(AlpineCatalog.sha256Url(abi))
            if (expected == null) {
                val f = BootstrapState.Failed("Could not read checksum sidecar — refusing to extract.", retryable = true)
                onState(f); return@withContext f
            }
            if (!Sha256.verify(tarball, expected)) {
                tarball.delete()
                val f = BootstrapState.Failed("Checksum mismatch — deleted corrupt tarball. Retry to re-download.", retryable = true)
                onState(f); return@withContext f
            }
        } catch (e: Exception) {
            val f = BootstrapState.Failed("Checksum verification failed: ${e.message}", retryable = true)
            onState(f); return@withContext f
        }

        // 3. Extract to staging (wipe stale staging first, like TermuxInstaller).
        // NOTE: toybox tar on-device differs from GNU tar — keep flags minimal
        // (-xzf only; -p tries to restore ownership and misbehaves here).
        onState(BootstrapState.InProgress("Extracting rootfs (${tarball.length() / 1024 / 1024}MB)…"))
        try {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            val tarVer = try {
                ProotRunner.exec(listOf("tar", "--version"), timeoutSec = 10).let {
                    (it.stdout.ifBlank { it.stderr }).trim().take(120).replace("\n", " ")
                }
            } catch (_: Exception) { "unknown" }
            val res = ProotRunner.exec(
                listOf("tar", "-xzf", tarball.absolutePath, "-C", staging.absolutePath),
                timeoutSec = 300
            )
            val entries = try {
                staging.list()?.sorted()?.take(25)?.joinToString(",") ?: "<empty>"
            } catch (_: Exception) { "<unlistable>" }
            val shOk = File(staging, "bin/sh").exists() || File(staging, "bin/busybox").exists()
            if (res.exitCode != 0 || !shOk) {
                val f = BootstrapState.Failed(
                    "Extraction failed: tar=$tarVer exit=${res.exitCode} " +
                        "tarball=${tarball.length()}B top=[$entries] " +
                        "err=${res.stderr.take(300)} out=${res.stdout.take(300)}",
                    retryable = true
                )
                onState(f); return@withContext f
            }
            // Minimal resolv.conf so apk works inside proot later.
            try {
                val resolv = File(staging, "etc/resolv.conf")
                resolv.parentFile?.mkdirs()
                if (!resolv.exists()) resolv.writeText("nameserver 1.1.1.1\n")
            } catch (_: Exception) {}
        } catch (e: Exception) {
            val f = BootstrapState.Failed("Extraction failed: ${e.message}", retryable = true)
            onState(f); return@withContext f
        }

        // 4. Atomic promotion staging -> rootfs + marker.
        try {
            if (rootfs.exists()) rootfs.deleteRecursively()
            if (!staging.renameTo(rootfs)) {
                val f = BootstrapState.Failed("Could not promote staged rootfs.", retryable = true)
                onState(f); return@withContext f
            }
            markerFile(filesDir).writeText("${AlpineCatalog.VERSION} ${abi.rootfsArch}\n")
        } catch (e: Exception) {
            val f = BootstrapState.Failed("Finalizing rootfs failed: ${e.message}", retryable = true)
            onState(f); return@withContext f
        }

        onState(BootstrapState.Ready)
        BootstrapState.Ready
    }

    /**
     * Bare proot smoke test: resolves the bundled proot (see [ProotSetup]),
     * then runs `proot <rootfs> /bin/sh -c 'echo proot-ok'`. Returns null only
     * when the rootfs itself is missing; setup failures are returned as
     * exit -1 results so the UI can show the reason. Never throws.
     */
    suspend fun prootSmokeTest(
        ctx: android.content.Context,
        onLog: (String) -> Unit = {}
    ): ProotRunner.Result? = withContext(Dispatchers.IO) {
        try {
            val rootfs = rootfsDir(ctx.filesDir)
            if (!File(rootfs, "bin/sh").exists() && !File(rootfs, "bin/busybox").exists()) {
                return@withContext null
            }
            val resolved = ProotSetup.resolve(ctx, onLog).getOrElse {
                return@withContext ProotRunner.Result(-1, "", "proot setup failed: ${it.message}")
            }
            ProotRunner.exec(
                ProotRunner.buildCommand(resolved.proot, rootfs),
                extraEnv = resolved.env
            )
        } catch (e: Exception) {
            ProotRunner.Result(-1, "", "smoke test crashed: ${e.message}")
        }
    }

    internal fun downloadUrl(url: String, dest: File, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }) {
        val tmp = File(dest.absolutePath + ".tmp")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "OpenCodeLauncher/0.1")
            }
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code for $url")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) throw java.io.IOException("Could not finalize download")
        } finally {
            try { tmp.takeIf { it.exists() && !dest.exists() }?.delete() } catch (_: Exception) {}
            conn?.disconnect()
        }
    }

    internal fun fetchExpectedSha256(shaUrl: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(shaUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText()
            Sha256.parseSidecar(body)
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
