package com.s17labs.opencodelauncher.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 5 (git auth), part 1: install `github-cli` inside the Alpine rootfs.
 *
 * Kept separate from [GuestSetup] (Phase 2) on purpose: existing installs
 * already carry a `.guest-setup-done` marker without gh, and re-running the
 * whole Node/npm install just to add one apk would waste many minutes.
 * This step is idempotent — safe to re-run / retry — and records its own
 * marker with the installed gh version.
 */
object GitSetup {

    fun markerFile(filesDir: File): File = File(filesDir, "proot-env/.gh-setup-done")

    fun isDone(filesDir: File): Boolean = markerFile(filesDir).exists()

    fun installedVersion(filesDir: File): String? = try {
        markerFile(filesDir).takeIf { it.exists() }?.readText()?.trim()?.ifBlank { null }
    } catch (_: Exception) {
        null
    }

    /**
     * Parse `gh --version` output ("gh version 2.78.0 (2025-11-...)" or a bare
     * "2.78.0") — null when no version token is found.
     */
    fun parseGhVersion(output: String): String? {
        return output.split(Regex("\\s+")).firstOrNull {
            it.matches(Regex("\\d+\\.\\d+\\.\\d+.*"))
        }
    }

    suspend fun install(
        ctx: Context,
        onState: (BootstrapState) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): BootstrapState = withContext(Dispatchers.IO) {
        try {
            if (!RuntimeBootstrap.isBootstrapDone(ctx.filesDir)) {
                val f = BootstrapState.Failed(
                    "Rootfs not ready — run bootstrap first. (${RuntimeBootstrap.diagnoseRootfs(ctx.filesDir)})",
                    retryable = true
                )
                onState(f); return@withContext f
            }
            val resolved = ProotSetup.resolve(ctx, onLog).getOrElse {
                val f = BootstrapState.Failed("proot setup failed: ${it.message}", retryable = true)
                onState(f); return@withContext f
            }
            val rootfs = RuntimeBootstrap.rootfsDir(ctx.filesDir)
            GuestSetup.ensureResolv(rootfs)

            onState(BootstrapState.InProgress("Installing GitHub CLI…"))
            onLog("$ apk add --no-cache github-cli")
            val r = ProotRunner.exec(
                ProotRunner.buildCommand(
                    resolved.proot, rootfs,
                    guestCmd = GuestSetup.guestShell("apk add --no-cache github-cli")
                ),
                extraEnv = resolved.env,
                timeoutSec = 600
            )
            r.stdout.lineSequence().filter { it.isNotBlank() }.toList().takeLast(10).forEach { onLog("  $it") }
            if (r.exitCode != 0) {
                val tail = r.stderr.ifBlank { r.stdout }.trim().lines().takeLast(8).joinToString("\n").take(600)
                val f = BootstrapState.Failed("Install GitHub CLI failed (exit ${r.exitCode}):\n$tail", retryable = true)
                onState(f); return@withContext f
            }

            val v = ProotRunner.exec(
                ProotRunner.buildCommand(
                    resolved.proot, rootfs,
                    guestCmd = GuestSetup.guestShell("gh --version")
                ),
                extraEnv = resolved.env,
                timeoutSec = 60
            )
            val version = parseGhVersion(v.stdout.ifBlank { v.stderr })
            if (v.exitCode != 0 || version == null) {
                val f = BootstrapState.Failed("Verify failed — `gh --version` gave nothing usable.", retryable = true)
                onState(f); return@withContext f
            }
            onLog("Installed: gh=$version")
            markerFile(ctx.filesDir).writeText("gh=$version\n")
            onState(BootstrapState.Ready)
            BootstrapState.Ready
        } catch (e: Exception) {
            val f = BootstrapState.Failed("GitHub CLI install crashed: ${e.message}", retryable = true)
            onState(f); f
        }
    }
}
