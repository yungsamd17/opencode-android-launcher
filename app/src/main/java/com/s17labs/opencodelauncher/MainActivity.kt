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
    private var logText: String by mutableStateOf("Phase 0 skeleton. Phase 1 adds real bootstrap logs here.")
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
                            onTestBootstrap = { runBootstrapPoc() },
                            onRetry = { runBootstrapPoc() },
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
            val snapshot = log.toString().takeLast(4000)
            runOnUiThread { logText = snapshot }
        }
        io.launch {
            runOnUiThread { bootstrapState = BootstrapState.InProgress("Starting bootstrap…") }
            append("ABI: ${(Build.SUPPORTED_ABIS?.joinToString() ?: "?")}")
            if (RuntimeBootstrap.isBootstrapDone(filesDir)) {
                append("Marker present — rootfs already extracted, running proot smoke test…")
            }
            val final = RuntimeBootstrap.bootstrap(filesDir) { s ->
                runOnUiThread { bootstrapState = s }
                if (s is BootstrapState.InProgress) append(s.stepLabel)
            }
            runOnUiThread { bootstrapState = final }
            when (final) {
                is BootstrapState.Ready -> {
                    append("Rootfs ready. Looking for proot binary…")
                    val smoke = RuntimeBootstrap.prootSmokeTest(filesDir, nativeLibDir = applicationInfo.nativeLibraryDir?.let { java.io.File(it) })
                    if (smoke == null) {
                        append("No proot binary found yet (expected — Phase 1 still needs the Termux-built proot binary). Rootfs extract OK.")
                        runOnUiThread {
                            bootstrapState = BootstrapState.Failed(
                                "Rootfs extracted, but no proot binary yet — next: bundle a Termux-built proot and re-run smoke test.",
                                retryable = true
                            )
                        }
                    } else {
                        append("proot exit=${smoke.exitCode} stdout=${smoke.stdout.trim().take(300)} stderr=${smoke.stderr.trim().take(300)}")
                    }
                }
                is BootstrapState.Failed -> append("FAILED: ${final.message}")
                else -> {}
            }
        }
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
