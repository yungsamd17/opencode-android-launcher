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
        // NOTE: no "--" separator — this proot build rejects it
        // ("unknown option '--'"); parsing stops at the first non-option
        // (the guest program), so the rest passes through untouched.
        cmd += listOf("--cwd=/") + guestCmd
        return cmd
    }

    fun exec(
        cmd: List<String>,
        workDir: File? = null,
        timeoutSec: Long = 30,
        extraEnv: Map<String, String> = emptyMap()
    ): Result = execInternal(cmd, stdinText = null, workDir, timeoutSec, extraEnv)

    /**
     * Like [exec] but feeds [stdinText] to the process (for scripted
     * interactive prompts such as `gh auth login` — Phase 5 git auth).
     * Stdin is written fully, then closed, before waiting — callers must keep
     * the script short so nothing blocks on a full pipe.
     */
    fun execWithStdin(
        cmd: List<String>,
        stdinText: String,
        workDir: File? = null,
        timeoutSec: Long = 600,
        extraEnv: Map<String, String> = emptyMap()
    ): Result = execInternal(cmd, stdinText = stdinText, workDir, timeoutSec, extraEnv)

    private fun execInternal(
        cmd: List<String>,
        stdinText: String?,
        workDir: File?,
        timeoutSec: Long,
        extraEnv: Map<String, String>
    ): Result {
        // Never throw: callers run on a bare coroutine with no handler, and a
        // spawn failure (e.g. EACCES) must land in the UI, not in a crash box.
        return try {
            execStreaming(cmd, stdinText, workDir, timeoutSec, extraEnv)
        } catch (e: Exception) {
            Result(-1, "", "exec failed: ${e.message}")
        }
    }

    /**
     * Full-duplex variant: feeds [stdinText], and calls [onLine] for every
     * output line **as it arrives** ("out"/"err" tag) — the device-code flow
     * needs the code live, while the process is still polling. [onLine] runs
     * on pump threads; callers must thread-hop themselves. Never throws.
     */
    fun execStreaming(
        cmd: List<String>,
        stdinText: String? = null,
        workDir: File? = null,
        timeoutSec: Long = 600,
        extraEnv: Map<String, String> = emptyMap(),
        onLine: (stream: String, line: String) -> Unit = { _, _ -> }
    ): Result {
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
        fun pump(stream: java.io.InputStream, tag: String) {
            try {
                stream.bufferedReader().forEachLine { line ->
                    if (tag == "out") stdout.appendLine(line) else stderr.appendLine(line)
                    try {
                        onLine(tag, line)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        val tOut = Thread({ pump(proc.inputStream, "out") }, "proot-stdout").apply { isDaemon = true; start() }
        val tErr = Thread({ pump(proc.errorStream, "err") }, "proot-stderr").apply { isDaemon = true; start() }
        if (stdinText != null) {
            try {
                proc.outputStream.bufferedWriter().use { it.write(stdinText) }
            } catch (_: Exception) {}
        }
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
