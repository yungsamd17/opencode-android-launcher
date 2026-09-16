package com.s17labs.opencodelauncher

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.s17labs.opencodelauncher.runtime.BootstrapState
import com.s17labs.opencodelauncher.runtime.GitAuth
import com.s17labs.opencodelauncher.runtime.GitSetup
import com.s17labs.opencodelauncher.runtime.GuestSetup
import com.s17labs.opencodelauncher.runtime.RuntimeBootstrap
import com.s17labs.opencodelauncher.service.OpenCodeForegroundService
import com.s17labs.opencodelauncher.service.OpenCodeStatus
import com.s17labs.opencodelauncher.service.Power
import com.s17labs.opencodelauncher.storage.ProjectStore
import com.s17labs.opencodelauncher.storage.StorageAccess
import com.s17labs.opencodelauncher.ui.HomeScreen
import com.s17labs.opencodelauncher.ui.GitScreen
import com.s17labs.opencodelauncher.ui.OpenCodeLauncherTheme
import com.s17labs.opencodelauncher.ui.ProjectScreen
import com.s17labs.opencodelauncher.ui.SettingsScreen
import com.s17labs.opencodelauncher.ui.SetupScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val PROJECT = "project"
    const val GIT = "git"
    const val SETTINGS = "settings"
}

class MainActivity : ComponentActivity() {

    private var bootstrapState: BootstrapState by mutableStateOf(BootstrapState.NotStarted)
    private var guestStatus: String by mutableStateOf("Packages: not installed")
    private var batteryExempt: Boolean by mutableStateOf(false)
    private var serviceLog: String by mutableStateOf("")
    private var projectLabel: String by mutableStateOf("Guest home — no folder picked")
    private var hasStorageAccess: Boolean by mutableStateOf(false)
    private var ghStatus: String by mutableStateOf("GitHub: CLI not installed")
    private var ghAuthed: Boolean by mutableStateOf(false)
    private var ghBusyLabel: String? by mutableStateOf(null)
    private var ghCode: String? by mutableStateOf(null)
    private var ghUrl: String? by mutableStateOf(null)
    private var logText: String by mutableStateOf("Phase 2 done (opencode 1.18.30 on-device). Phase 3 runs it as a service.")
    private val io = CoroutineScope(Dispatchers.IO)

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result only gates the notification shade, not the service */ }

    /** All-files-access settings screen returns no meaningful result — refresh on return. */
    private val storageGrant = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshDerivedState() }

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
                val svc by OpenCodeStatus.state.collectAsStateWithLifecycle()
                val (statusText, boundUrl, detail) = when (val s = svc) {
                    is OpenCodeStatus.Value.Stopped -> Triple("Stopped", null, null)
                    is OpenCodeStatus.Value.Starting -> Triple("Starting…", null, null)
                    is OpenCodeStatus.Value.Running -> Triple("Running", s.url, null)
                    is OpenCodeStatus.Value.Failed -> Triple("Failed", null, s.message)
                }
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
                            onGoGit = { nav.navigate(Routes.GIT) },
                            onContinue = { nav.navigate(Routes.HOME) }
                        )
                    }
                    composable(Routes.HOME) {
                        HomeScreen(
                            status = statusText,
                            boundUrl = boundUrl,
                            detail = detail,
                            projectLabel = projectLabel,
                            onOpenOpenCode = { openInBrowser(boundUrl) },
                            onStartService = { startOpencodeService() },
                            onStopService = { stopOpencodeService() },
                            onChangeProject = { nav.navigate(Routes.PROJECT) },
                            onGoSetup = { nav.navigate(Routes.SETUP) },
                            onGoSettings = { nav.navigate(Routes.SETTINGS) }
                        )
                    }
                    composable(Routes.PROJECT) {
                        ProjectScreen(
                            root = StorageAccess.sharedRoot(),
                            current = ProjectStore.get(this@MainActivity),
                            onPick = { dir ->
                                ProjectStore.set(this@MainActivity, dir)
                                refreshDerivedState()
                                nav.popBackStack()
                            },
                            onGrantStorage = {
                                try {
                                    storageGrant.launch(StorageAccess.requestIntent(this@MainActivity))
                                } catch (_: Exception) {}
                            },
                            hasAccess = hasStorageAccess,
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable(Routes.GIT) {
                        GitScreen(
                            ghStatus = ghStatus,
                            authed = ghAuthed,
                            busyLabel = ghBusyLabel,
                            deviceCode = ghCode,
                            deviceUrl = ghUrl,
                            onInstallGh = { runGitSetup() },
                            onConnect = { runGitLogin() },
                            onOpenDeviceUrl = { openInBrowser(ghUrl) },
                            onLogout = { runGitLogout() },
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable(Routes.SETTINGS) {
                        SettingsScreen(
                            logText = logText,
                            serviceLog = serviceLog,
                            batteryExempt = batteryExempt,
                            onWipeRootfs = { wipeRootfs() },
                            onShareLog = { shareLog() },
                            onBatteryExemption = { Power.requestExemption(this@MainActivity) },
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

    /** Phase 5 (git auth): install `github-cli` inside the guest. Idempotent. */
    private fun runGitSetup() {
        io.launch {
            runOnUiThread { ghBusyLabel = "Installing GitHub CLI…" }
            try {
                val final = GitSetup.install(
                    this@MainActivity,
                    onState = { s ->
                        if (s is BootstrapState.InProgress) runOnUiThread { ghBusyLabel = s.stepLabel }
                    },
                    onLog = { appendLog(it) }
                )
                if (final is BootstrapState.Failed) appendLog("GitHub CLI: ${final.message}")
            } catch (e: Exception) {
                appendLog("GitHub CLI install crashed: ${e.message}")
            }
            runOnUiThread { ghBusyLabel = null }
            refreshDerivedState()
        }
    }

    /** Phase 5 (git auth): device-code login — code + link appear on GitScreen. */
    private fun runGitLogin() {
        io.launch {
            runOnUiThread {
                ghBusyLabel = "Waiting for browser approval…"
                ghCode = null
                ghUrl = null
            }
            try {
                val st = GitAuth.login(
                    this@MainActivity,
                    onLog = { appendLog(it) },
                    onDeviceFlow = { flow ->
                        runOnUiThread {
                            ghCode = flow.code
                            ghUrl = flow.url
                        }
                    }
                )
                if (st.authed) {
                    appendLog("GitHub: ${st.summary}")
                    runOnUiThread {
                        ghCode = null
                        ghUrl = null
                    }
                } else {
                    appendLog("GitHub: ${st.summary}")
                }
            } catch (e: Exception) {
                appendLog("GitHub login crashed: ${e.message}")
            }
            runOnUiThread { ghBusyLabel = null }
            refreshDerivedState()
        }
    }

    private fun runGitLogout() {
        io.launch {
            runOnUiThread { ghBusyLabel = "Logging out…" }
            try {
                val st = GitAuth.logout(this@MainActivity, onLog = { appendLog(it) })
                appendLog("GitHub: ${st.summary}")
            } catch (e: Exception) {
                appendLog("GitHub logout crashed: ${e.message}")
            }
            runOnUiThread { ghBusyLabel = null }
            refreshDerivedState()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDerivedState()
    }

    /** Refresh cheap derived state (guest marker, battery exemption, service log tail). */
    private fun refreshDerivedState() {
        io.launch {
            val guest = try {
                val m = java.io.File(filesDir, "proot-env/.guest-setup-done")
                if (m.exists()) "Packages: ${m.readText().trim()}" else "Packages: not installed"
            } catch (_: Exception) { "Packages: unknown" }
            val exempt = Power.isExempt(this@MainActivity)
            val access = try {
                StorageAccess.hasFullAccess(this@MainActivity)
            } catch (_: Exception) { false }
            val project = ProjectStore.get(this@MainActivity)?.absolutePath
                ?: "Guest home — no folder picked"
            // GitHub status: file markers first (cheap), then one proot probe
            // only when the CLI is actually installed.
            val (gh, authed) = try {
                if (GitSetup.isDone(filesDir)) {
                    val ver = GitSetup.installedVersion(filesDir) ?: "?"
                    val auth = GitAuth.status(this@MainActivity) {}
                    "GitHub CLI: $ver · ${auth.summary}" to auth.authed
                } else {
                    "GitHub: CLI not installed" to false
                }
            } catch (_: Exception) {
                "GitHub: unknown" to false
            }
            val slog = try {
                val f = java.io.File(filesDir, "opencode-service.log")
                if (f.exists()) f.readText().takeLast(4000) else ""
            } catch (_: Exception) { "" }
            runOnUiThread {
                guestStatus = guest
                batteryExempt = exempt
                hasStorageAccess = access
                projectLabel = project
                ghStatus = gh
                ghAuthed = authed
                serviceLog = slog
            }
        }
    }

    private fun startOpencodeService() {
        // Notification shade permission (Android 13+): request, but start anyway.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        // Battery exemption is explicit: one system dialog, user decides.
        // Doze would otherwise kill the server within the hour.
        if (!Power.isExempt(this)) {
            Power.requestExemption(this)
        }
        startForegroundService(OpenCodeForegroundService.startIntent(this))
    }

    private fun stopOpencodeService() {
        startService(OpenCodeForegroundService.stopIntent(this))
    }

    private fun openInBrowser(url: String?) {
        url ?: return
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
