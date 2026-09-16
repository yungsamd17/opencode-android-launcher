package com.s17labs.opencodelauncher.runtime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 5 (git auth), part 2: GitHub login via the device-code flow.
 *
 * Once `gh` is installed ([GitSetup]), `gh auth login` prints a one-time
 * code plus a `github.com/login/device` link — the same "here's a link, open
 * your browser" pattern the app already uses for the opencode web UI, so no
 * custom OAuth redirect handling is needed (per plan Phase 5 and the
 * no-custom-OAuth rule in AGENTS.md).
 *
 * The login prompts are answered with their defaults (GitHub.com, HTTPS,
 * store git credentials, browser auth) via [LOGIN_STDIN]; the browser can't
 * open from inside proot, so gh falls back to printing the code and polling
 * until the user approves (or the code expires, ~15 min — we cap at 10).
 * Credentials land in the guest's /root/.config/gh (app-private storage,
 * same trust boundary as any locally-stored token on a non-rooted device).
 *
 * Fragility note: the prompt script depends on gh's question order. The full
 * transcript streams to onLog, so a gh-side change shows up as a readable
 * failure instead of a hang.
 */
object GitAuth {

    /** Four Enters = accept all `gh auth login` defaults (see class doc). */
    const val LOGIN_STDIN = "\n\n\n\n"

    /** Device codes expire (~15 min); 10 min cap keeps the UX sane. */
    const val LOGIN_TIMEOUT_SEC = 600L

    const val FALLBACK_DEVICE_URL = "https://github.com/login/device"

    private val CODE_RE = Regex("""\b[A-Z0-9]{4}-[A-Z0-9]{4}\b""")
    private val DEVICE_URL_RE = Regex("""https?://[^\s'"]*github\.com/login/device[^\s'"]*""")
    private val ACCOUNT_RE = Regex("""account\s+(\S+)""")

    data class DeviceFlow(val code: String, val url: String)

    data class Status(val authed: Boolean, val summary: String)

    /**
     * Pull the one-time code + verification URL out of `gh auth login`
     * output. gh prints several wordings across versions ("copy your
     * one-time code: XXXX-XXXX", "Enter code: XXXX-XXXX", a URL with the
     * code embedded) — the CODE_RE shape is what stays stable.
     */
    fun parseDeviceFlow(output: String): DeviceFlow? {
        val code = CODE_RE.find(output)?.value ?: return null
        val url = DEVICE_URL_RE.find(output)?.value?.trimEnd('.', ',', ')', ';')
            ?: FALLBACK_DEVICE_URL
        return DeviceFlow(code, url)
    }

    /** Parse `gh auth status` output ("Logged in to github.com account foo ..."). */
    fun parseStatus(exitCode: Int, output: String): Status {
        if (exitCode != 0) return Status(false, "not logged in")
        val account = ACCOUNT_RE.find(output)?.groupValues?.getOrNull(1)
        return Status(true, if (account != null) "logged in as $account" else "logged in")
    }

    private suspend fun prootGh(
        ctx: Context,
        guestSh: String,
        stdinText: String? = null,
        timeoutSec: Long = 60,
        onLog: (String) -> Unit,
        onLine: ((stream: String, line: String) -> Unit)? = null
    ): ProotRunner.Result? = withContext(Dispatchers.IO) {
        if (!GitSetup.isDone(ctx.filesDir)) {
            onLog("GitHub CLI not installed — install it first.")
            return@withContext null
        }
        val resolved = ProotSetup.resolve(ctx, onLog).getOrElse {
            onLog("proot setup failed: ${it.message}")
            return@withContext null
        }
        val rootfs = RuntimeBootstrap.rootfsDir(ctx.filesDir)
        GuestSetup.ensureResolv(rootfs)
        val cmd = ProotRunner.buildCommand(
            resolved.proot, rootfs,
            guestCmd = GuestSetup.guestShell(guestSh)
        )
        if (onLine != null) {
            ProotRunner.execStreaming(cmd, stdinText, timeoutSec = timeoutSec, extraEnv = resolved.env, onLine = onLine)
        } else if (stdinText != null) {
            ProotRunner.execWithStdin(cmd, stdinText, timeoutSec = timeoutSec, extraEnv = resolved.env)
        } else {
            ProotRunner.exec(cmd, timeoutSec = timeoutSec, extraEnv = resolved.env)
        }
    }

    /**
     * Run the device flow. Streams the transcript to [onLog] line-by-line and
     * reports the code/link via [onDeviceFlow] as soon as they appear (the
     * process keeps polling until approval, so post-hoc parsing would be too
     * late). [onDeviceFlow] may fire twice — first with the fallback URL,
     * then with gh's real one — callers must treat it as idempotent, and both
     * callbacks may arrive on background threads. Returns the final [Status].
     * Never throws — all failures become Status(false, …).
     */
    suspend fun login(
        ctx: Context,
        onLog: (String) -> Unit = {},
        onDeviceFlow: (DeviceFlow) -> Unit = {}
    ): Status = withContext(Dispatchers.IO) {
        try {
            onLog("$ gh auth login  (device code — approve in your browser)")
            val seen = StringBuilder()
            var announced: DeviceFlow? = null
            val r = prootGh(
                ctx, "gh auth login", LOGIN_STDIN, LOGIN_TIMEOUT_SEC, onLog,
                onLine = { _, line ->
                    val clean = line.take(300)
                    if (clean.isNotBlank()) onLog("  $clean")
                    synchronized(seen) {
                        seen.appendLine(clean)
                        if (seen.length < 60_000) {
                            val flow = parseDeviceFlow(seen.toString())
                            if (flow != null && flow != announced) {
                                announced = flow
                                try {
                                    onDeviceFlow(flow)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            ) ?: return@withContext Status(false, "GitHub CLI not installed")
            if (r.exitCode == 124) {
                return@withContext Status(false, "timed out waiting — approve faster, or connect again for a fresh code")
            }
            if (r.exitCode != 0) {
                val tail = r.stderr.ifBlank { r.stdout }.trim().lines().takeLast(5).joinToString("\n").take(400)
                return@withContext Status(false, "login failed (exit ${r.exitCode}): $tail")
            }
            // Login accepted — confirm what gh thinks the state is.
            status(ctx, onLog)
        } catch (e: Exception) {
            Status(false, "login crashed: ${e.message}")
        }
    }

    suspend fun status(ctx: Context, onLog: (String) -> Unit = {}): Status =
        withContext(Dispatchers.IO) {
            try {
                val r = prootGh(ctx, "gh auth status", timeoutSec = 60, onLog = onLog)
                    ?: return@withContext Status(false, "GitHub CLI not installed")
                parseStatus(r.exitCode, r.stdout.ifBlank { r.stderr })
            } catch (e: Exception) {
                Status(false, "status check crashed: ${e.message}")
            }
        }

    suspend fun logout(ctx: Context, onLog: (String) -> Unit = {}): Status =
        withContext(Dispatchers.IO) {
            try {
                // `gh auth logout` asks [y/N] (default No) — answer yes; if a
                // future gh stops prompting, the stray line is harmless.
                val r = prootGh(ctx, "gh auth logout --hostname github.com", "y\n", 60, onLog)
                    ?: return@withContext Status(false, "GitHub CLI not installed")
                if (r.exitCode != 0) {
                    val tail = r.stderr.ifBlank { r.stdout }.trim().lines().takeLast(5).joinToString("\n").take(400)
                    return@withContext Status(false, "logout failed (exit ${r.exitCode}): $tail")
                }
                Status(false, "logged out")
            } catch (e: Exception) {
                Status(false, "logout crashed: ${e.message}")
            }
        }
}
