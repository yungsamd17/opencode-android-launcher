package com.s17labs.opencodelauncher

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.s17labs.opencodelauncher.runtime.BootstrapState
import com.s17labs.opencodelauncher.runtime.GuestSetup
import com.s17labs.opencodelauncher.runtime.RuntimeBootstrap
import com.s17labs.opencodelauncher.service.OpenCodeForegroundService
import com.s17labs.opencodelauncher.ui.HomeScreen
import com.s17labs.opencodelauncher.ui.OpenCodeLauncherTheme
import com.s17labs.opencodelauncher.ui.SettingsScreen
import com.s17labs.opencodelauncher.ui.SetupScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private var bootstrapState: BootstrapState by mutableStateOf(BootstrapState.NotStarted)
    private var boundUrl: String? by mutableStateOf(null)
    private var status: String by mutableStateOf("Stopped")
    private var guestStatus: String by mutableStateOf("Packages: not installed")
    private var logText: String by mutableStateOf("Phase 1 done (proot-ok on-device). Phase 2 installs guest packages here.")
    private val io = CoroutineScope(Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val abiResult = RuntimeBootstrap.detectAbi()
        val abiLabel = abiResult.fold(
            onSuccess = { it.name + " (" + (Build.SUPPORTED_ABIS?.joinToString() ?: "?") + ")" },
            onFailure = { "unsupported (${it.message})" }
        )
        if (abiResult.isFailure) {
            bootstrapState = BootstrapState.Failed(
                abiResult.exceptionOrNull()?.message ?: "Unsupported ABI",
                retryable = false
            )
        }

        setContent {
            OpenCodeLauncherTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = Routes.HOME) {
                    composable(Routes.SETUP) {
                        SetupScreen(
                            abiLabel = abiLabel,
                            state = bootstrapState,
                            logText = logText,
                            guestStatus = guestStatus,
                            onTestBootstrap = { runBootstrapPoc() },
                            onInstallPackages = { runGuestSetup() },
                            onRetry = { runBootstrapPoc() },
                            onShareLog = { shareLog() },
                            onContinue = { nav.navigate(Routes.HOME) }
                        )
                    }
                    composable(Routes.HOME) {
                        HomeScreen(
                            status = status,
                            boundUrl = boundUrl,
                            onOpenOpenCode = { openInBrowser() },
                            onStartService = { startOpencodeService() },
                            onStopService = { stopOpencodeService() },
                            onGoSetup = { nav.navigate(Routes.SETUP) },
                            onGoSettings = { nav.navigate(Routes.SETTINGS) }
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            logText = logText,
                            onWipeRootfs = { wipeRootfs() },
                            onShareLog = { shareLog() },
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    /**
     * Phase 1 proof-of-concept (plan's suggested next step): debug-only
     * "test bootstrap" button + raw log view. Runs the real
     * download → verify → extract flow, then attempts a bare
     * `proot <alpine-rootfs> /bin/sh` smoke test via ProcessBuilder.
     * Phase 1 is NOT done until that smoke test succeeds on real aarch64.
     */
    private fun runBootstrapPoc() {
        val log = StringBuilder()
        fun append(msg: String) {
            log.appendLine(msg)
            try {
                java.io.File(filesDir, "bootstrap.log").appendText(msg + "\n")
            } catch (_: Exception) {}
            val snapshot = log.toString().takeLast(6000)
            runOnUiThread { logText = snapshot }
        }
        io.launch {
            try {
                runOnUiThread { bootstrapState = BootstrapState.InProgress("Starting bootstrap…") }
                append("ABI: ${(Build.SUPPORTED_ABIS?.joinToString() ?: "?")}")
                append("nativeLibDir: ${this@MainActivity.applicationInfo.nativeLibraryDir}")
                if (RuntimeBootstrap.isBootstrapDone(filesDir)) {
                    append("Marker present — rootfs already extracted, running proot smoke test…")
                }
                val final = try {
                    RuntimeBootstrap.bootstrap(filesDir) { s ->
                        runOnUiThread { bootstrapState = s }
                        if (s is BootstrapState.InProgress) append(s.stepLabel)
                    }
                } catch (e: Exception) {
                    BootstrapState.Failed("Bootstrap crashed: ${e.message}", retryable = true)
                }
                runOnUiThread { bootstrapState = final }
                when (final) {
                    is BootstrapState.Ready -> {
                        append("Rootfs ready. Resolving proot binary…")
                        val smoke = RuntimeBootstrap.prootSmokeTest(this@MainActivity, onLog = { append(it) })
                        if (smoke == null) {
                            append("Rootfs missing — wipe and re-run setup.")
                        } else {
                            append("proot exit=${smoke.exitCode} stdout=${smoke.stdout.trim().take(300)} stderr=${smoke.stderr.trim().take(300)}")
                            if (smoke.exitCode == 0 && smoke.stdout.contains("proot-ok")) {
                                append("SMOKE TEST PASSED — Phase 1 milestone reached.")
                            } else if (smoke.exitCode != 0) {
                                runOnUiThread {
                                    bootstrapState = BootstrapState.Failed(
                                        "proot smoke test failed (exit ${smoke.exitCode}): ${(smoke.stderr.ifBlank { smoke.stdout }).take(300)}",
                                        retryable = true
                                    )
                                }
                            }
                        }
                    }
                    is BootstrapState.Failed -> append("FAILED: ${final.message}")
                    else -> {}
                }
            } catch (e: Exception) {
                val msg = "Test run crashed (no system crash box expected): ${e.message}"
                append(msg)
                runOnUiThread { bootstrapState = BootstrapState.Failed(msg, retryable = true) }
            }
        }
    }

    private fun runGuestSetup() {
        io.launch {
            try {
                if (!RuntimeBootstrap.isBootstrapDone(filesDir)) {
                    appendLog("Rootfs missing - running bootstrap first…")
                    runOnUiThread { bootstrapState = BootstrapState.InProgress("Bootstrapping rootfs first…") }
                    val b = try {
                        RuntimeBootstrap.bootstrap(filesDir) { s ->
                            runOnUiThread { bootstrapState = s }
                            if (s is BootstrapState.InProgress) appendLog(s.stepLabel)
                        }
                    } catch (e: Exception) {
                        BootstrapState.Failed("Bootstrap crashed: ${e.message}", retryable = true)
                    }
                    runOnUiThread { bootstrapState = b }
                    if (b !is BootstrapState.Ready) {
                        appendLog("FAILED: bootstrap did not complete - fix that first.")
                        return@launch
                    }
                }
                val final = GuestSetup.install(
                    this@MainActivity,
                    onState = { runOnUiThread { bootstrapState = it } },
                    onLog = { appendLog(it) }
                )
                if (final is BootstrapState.Ready) {
                    val summary = try {
                        java.io.File(filesDir, "proot-env/.guest-setup-done").readText().trim()
                    } catch (_: Exception) { "done" }
                    runOnUiThread { guestStatus = "Packages: $summary" }
                    appendLog("PHASE 2 DONE - $summary")
                } else if (final is BootstrapState.Failed) {
                    appendLog("FAILED: ${final.message}")
                }
            } catch (e: Exception) {
                val msg = "Package install crashed: ${e.message}"
                appendLog(msg)
                runOnUiThread { bootstrapState = BootstrapState.Failed(msg, retryable = true) }
            }
        }
    }

    private fun appendLog(msg: String) {
        try {
            java.io.File(filesDir, "bootstrap.log").appendText(msg + "\n")
        } catch (_: Exception) {}
        runOnUiThread { logText = (logText + "\n" + msg).takeLast(6000) }
    }

    private fun startOpencodeService() {
        status = "Starting…"
        startForegroundService(OpenCodeForegroundService.startIntent(this))
        status = "Service requested (Phase 3 runs opencode web here)"
    }

    private fun stopOpencodeService() {
        startService(OpenCodeForegroundService.stopIntent(this))
        status = "Stopped"
        boundUrl = null
    }

    private fun openInBrowser() {
        val url = boundUrl ?: return
        try {
            val tabs = CustomTabsIntent.Builder().build()
            tabs.launchUrl(this, url.toUri())
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    private fun shareLog() {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OpenCode Launcher bootstrap log")
                putExtra(Intent.EXTRA_TEXT, logText.ifBlank { "No logs yet." })
            }
            startActivity(Intent.createChooser(send, "Share bootstrap log"))
        } catch (_: Exception) {}
    }

    private fun wipeRootfs() {
        io.launch {
            try {
                val dir = java.io.File(filesDir, "proot-env")
                if (dir.exists()) dir.deleteRecursively()
                runOnUiThread {
                    logText = "Wiped ${dir.absolutePath}. Re-run setup."
                    bootstrapState = BootstrapState.NotStarted
                }
            } catch (e: Exception) {
                runOnUiThread { logText = "Wipe failed: ${e.message}" }
            }
        }
    }
}
