package com.s17labs.opencodelauncher.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 2: one-time guest package install inside the Alpine rootfs.
 *
 * Runs via the same proot path proven in Phase 1 (no native UI, no custom
 * package manager — just apk + npm as OpenCode's install docs specify):
 *   1. apk update
 *   2. apk add --no-cache nodejs npm git openssh
 *   3. npm install -g opencode-ai   (official registry, never a bundled binary)
 *   4. verify: node/npm/opencode --version
 *
 * Idempotent (safe to re-run / retry): apk and npm -g tolerate repeats.
 * Completion is recorded in .guest-setup-done with the installed versions.
 * Every step streams its output to [onLog] and surfaces failures with the
 * tail of guest stderr — the Setup UI shows a Retry action, never a silent
 * infinite retry loop.
 */
object GuestSetup {

    data class Step(
        val label: String,
        val cmd: List<String>,
        val timeoutSec: Long
    )

    fun markerFile(filesDir: File): File = File(filesDir, "proot-env/.guest-setup-done")

    fun isDone(filesDir: File): Boolean = markerFile(filesDir).exists()

    /** Parse `node --version` style output ("v22.14.0") — null when unparseable. */
    fun parseVersionLine(output: String): String? {
        val token = output.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
        return if (token.matches(Regex("v?\\d+\\.\\d+\\.\\d+.*"))) token else null
    }

    suspend fun install(
        ctx: Context,
        onState: (BootstrapState) -> Unit = {},
        onLog: (String) -> Unit = {}
    ): BootstrapState = withContext(Dispatchers.IO) {
        try {
            if (!RuntimeBootstrap.isBootstrapDone(ctx.filesDir)) {
                val f = BootstrapState.Failed("Rootfs not ready — run bootstrap first.", retryable = true)
                onState(f); return@withContext f
            }
            val resolved = ProotSetup.resolve(ctx, onLog).getOrElse {
                val f = BootstrapState.Failed("proot setup failed: ${it.message}", retryable = true)
                onState(f); return@withContext f
            }
            val rootfs = RuntimeBootstrap.rootfsDir(ctx.filesDir)

            // apk needs a resolver; minirootfs may ship an empty one.
            try {
                val resolv = File(rootfs, "etc/resolv.conf")
                if (!resolv.exists() || resolv.readText().isBlank()) {
                    resolv.parentFile?.mkdirs()
                    resolv.writeText("nameserver 1.1.1.1\n")
                }
            } catch (_: Exception) {}

            fun guest(sh: String) = listOf("/bin/sh", "-c", sh)

            val steps = listOf(
                Step("Updating apk index…", guest("apk update"), 180),
                Step("Installing nodejs, npm, git, openssh…", guest("apk add --no-cache nodejs npm git openssh"), 900),
                Step("Installing opencode-ai from npm…", guest("npm install -g opencode-ai"), 1200)
            )
            for (s in steps) {
                onState(BootstrapState.InProgress(s.label))
                onLog("$ ${s.cmd.last()}")
                val r = ProotRunner.exec(
                    ProotRunner.buildCommand(resolved.proot, rootfs, guestCmd = s.cmd),
                    extraEnv = resolved.env,
                    timeoutSec = s.timeoutSec
                )
                r.stdout.lineSequence().filter { it.isNotBlank() }.toList().takeLast(15).forEach { onLog("  $it") }
                if (r.exitCode != 0) {
                    val tail = r.stderr.ifBlank { r.stdout }.trim().lines().takeLast(8).joinToString("\n").take(600)
                    val f = BootstrapState.Failed("${s.label} failed (exit ${r.exitCode}):\n$tail", retryable = true)
                    onState(f); return@withContext f
                }
            }

            onState(BootstrapState.InProgress("Verifying install…"))
            val versions = mapOf(
                "node" to "node --version",
                "npm" to "npm --version",
                "opencode" to "opencode --version"
            ).mapValues { (_, cmd) ->
                val r = ProotRunner.exec(
                    ProotRunner.buildCommand(resolved.proot, rootfs, guestCmd = guest(cmd)),
                    extraEnv = resolved.env,
                    timeoutSec = 120
                )
                if (r.exitCode != 0) return@mapValues null
                parseVersionLine(r.stdout.ifBlank { r.stderr })
            }
            if (versions.values.any { it == null }) {
                val missing = versions.filterValues { it == null }.keys.joinToString(", ")
                val f = BootstrapState.Failed("Verify failed — no version from: $missing", retryable = true)
                onState(f); return@withContext f
            }
            val summary = versions.entries.joinToString(" ") { "${it.key}=${it.value}" }
            onLog("Installed: $summary")
            markerFile(ctx.filesDir).writeText("$summary\n")
            onState(BootstrapState.Ready)
            BootstrapState.Ready
        } catch (e: Exception) {
            val f = BootstrapState.Failed("Package install crashed: ${e.message}", retryable = true)
            onState(f); f
        }
    }
}
