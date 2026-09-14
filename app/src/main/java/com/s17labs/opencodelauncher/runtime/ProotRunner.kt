package com.s17labs.opencodelauncher.runtime

import java.io.File

/**
 * Builds and runs `proot` invocations via ProcessBuilder.
 *
 * Phase 1 milestone: a bare `proot <rootfs> /bin/sh -c 'echo ok'`
 * succeeding on a real aarch64 device. No root, no emulator-only proof.
 *
 * The proot binary ships in jniLibs and is resolved via [ProotSetup] —
 * never from writable app-data dirs (exec there is denied on targetSdk 29+).
 * [exec] never throws: spawn/wait failures become exit-code -1 results so the
 * UI can show them instead of crashing.
 */
object ProotRunner {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun buildCommand(
        prootBin: File,
        rootfsDir: File,
        binds: Map<String, String> = emptyMap(),
        guestCmd: List<String> = listOf("/bin/sh", "-c", "echo proot-ok")
    ): List<String> {
        val cmd = mutableListOf(
            prootBin.absolutePath,
            "--rootfs=${rootfsDir.absolutePath}",
            "--link2symlink",
            "--kill-on-exit",
            "--bind=/proc",
            "--bind=/dev",
            "--bind=/sys"
        )
        for ((host, guest) in binds) {
            cmd += "--bind=$host:$guest"
        }
        // Isolate env inside guest; keep it minimal for the POC.
        cmd += listOf("--cwd=/", "--", *guestCmd.toTypedArray())
        return cmd
    }

    fun exec(
        cmd: List<String>,
        workDir: File? = null,
        timeoutSec: Long = 30,
        extraEnv: Map<String, String> = emptyMap()
    ): Result {
        // Never throw: callers run on a bare coroutine with no handler, and a
        // spawn failure (e.g. EACCES) must land in the UI, not in a crash box.
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(false)
            if (workDir != null) pb.directory(workDir)
            // Minimal sanitized env: don't leak host LD_* into guest, except the
            // caller-provided library path for our bundled proot deps.
            pb.environment().remove("LD_PRELOAD")
            pb.environment().putAll(extraEnv)
            val proc = pb.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val tOut = Thread { try { proc.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) } } catch (_: Exception) {} }
        val tErr = Thread { try { proc.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } } catch (_: Exception) {} }
        tOut.start(); tErr.start()
        val finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            tOut.join(2000); tErr.join(2000)
            return Result(124, stdout.toString(), (stderr.toString() + "\n[TIMEOUT after ${timeoutSec}s]").trim())
        }
        tOut.join(5000); tErr.join(5000)
        return Result(proc.exitValue(), stdout.toString(), stderr.toString())
        } catch (e: Exception) {
            return Result(-1, "", "exec failed: ${e.message}")
        }
    }
}
