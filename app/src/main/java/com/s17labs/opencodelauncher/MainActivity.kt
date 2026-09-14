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
     * Phase 1 proof-of-concept entry point (Suggested next step in the plan):
     * debug-only "test bootstrap" that will attempt a bare
     * `proot <alpine-rootfs> /bin/sh` via ProcessBuilder and stream raw logs.
     * Currently reports unimplemented — Phase 1 fills this in.
     */
    private fun runBootstrapPoc() {
        bootstrapState = BootstrapState.InProgress("Checking ABI…")
        io.launch {
            val abi = RuntimeBootstrap.detectAbi()
            runOnUiThread {
                bootstrapState = if (abi.isSuccess) {
                    BootstrapState.Failed(
                        "Bootstrap POC not implemented yet (Phase 1). ABI ${abi.getOrNull()?.name} detected OK — next: download Alpine minirootfs + proot.",
                        retryable = true
                    )
                } else {
                    BootstrapState.Failed(abi.exceptionOrNull()?.message ?: "ABI check failed", retryable = false)
                }
                logText = "ABI check: ${abi.fold({ it.name }, { "FAILED: ${it.message}" })}"
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
