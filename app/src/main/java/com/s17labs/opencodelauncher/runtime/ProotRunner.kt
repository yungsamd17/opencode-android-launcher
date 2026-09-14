package com.s17labs.opencodelauncher.runtime

import java.io.File

/**
 * Builds and runs `proot` invocations via ProcessBuilder.
 *
 * Phase 1 milestone: a bare `proot <rootfs> /bin/sh -c 'echo ok'`
 * succeeding on a real aarch64 device. No root, no emulator-only proof.
 *
 * The proot binary itself is NOT bundled from unknown provenance:
 * resolution order is explicit prootFile > app native lib dir > PATH lookup,
 * and failure surfaces a clear error naming where we looked.
 */
object ProotRunner {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String)

    fun resolveProot(explicit: File? = null, nativeLibDir: File? = null): File? {
        if (explicit != null && explicit.canExecute()) return explicit
        if (nativeLibDir != null) {
            val cand = File(nativeLibDir, "libproot.so")
            if (cand.exists() && cand.canExecute()) return cand
            val cand2 = File(nativeLibDir, "proot")
            if (cand2.exists() && cand2.canExecute()) return cand2
        }
        // PATH lookup via shell — keep shell use minimal and explicit.
        return try {
            val p = ProcessBuilder("sh", "-c", "command -v proot")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            if (out.isNotBlank()) File(out.substringAfterLast('\n').trim()).takeIf { it.exists() } else null
        } catch (_: Exception) {
            null
        }
    }

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

    fun exec(cmd: List<String>, workDir: File? = null, timeoutSec: Long = 30): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        if (workDir != null) pb.directory(workDir)
        // Minimal sanitized env: don't leak host LD_* into guest.
        pb.environment().remove("LD_PRELOAD")
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
    }
}
