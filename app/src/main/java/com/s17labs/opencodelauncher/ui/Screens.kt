package com.s17labs.opencodelauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.s17labs.opencodelauncher.runtime.BootstrapState

@Composable
fun SetupScreen(
    abiLabel: String,
    state: BootstrapState,
    onTestBootstrap: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Setup", style = MaterialTheme.typography.headlineMedium)
        Text("Device ABI: $abiLabel", style = MaterialTheme.typography.bodyMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is BootstrapState.NotStarted ->
                        Text("Runtime not installed yet. Phase 1 will download Alpine minirootfs + proot here.")
                    is BootstrapState.InProgress ->
                        Text("Step: ${state.stepLabel}")
                    is BootstrapState.Ready ->
                        Text("Runtime ready.")
                    is BootstrapState.Failed ->
                        Text("Failed: ${state.message}")
                }
                if (state is BootstrapState.InProgress) CircularProgressIndicator()
            }
        }
        Button(onClick = onTestBootstrap, modifier = Modifier.fillMaxWidth()) {
            Text("Test bootstrap (Phase 1 proof of concept)")
        }
        if (state is BootstrapState.Failed && state.retryable) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Home")
        }
    }
}

@Composable
fun HomeScreen(
    status: String,
    boundUrl: String?,
    onOpenOpenCode: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onGoSetup: () -> Unit,
    onGoSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("OpenCode Launcher", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status: $status", style = MaterialTheme.typography.titleMedium)
                Text(boundUrl ?: "URL appears here once opencode web is running.")
            }
        }
        Button(onClick = onOpenOpenCode, enabled = boundUrl != null, modifier = Modifier.fillMaxWidth()) {
            Text("Open OpenCode")
        }
        OutlinedButton(onClick = onStartService, modifier = Modifier.fillMaxWidth()) {
            Text("Start service")
        }
        OutlinedButton(onClick = onStopService, modifier = Modifier.fillMaxWidth()) {
            Text("Stop service")
        }
        OutlinedButton(onClick = onGoSetup, modifier = Modifier.fillMaxWidth()) {
            Text("Setup")
        }
        OutlinedButton(onClick = onGoSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Settings")
        }
        Text(
            "No native chat UI here by design — opencode web provides the full UI in your browser.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun SettingsScreen(
    logText: String,
    onWipeRootfs: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Logs", style = MaterialTheme.typography.titleMedium)
                Text(logText, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = onWipeRootfs, modifier = Modifier.fillMaxWidth()) {
            Text("Wipe rootfs (re-run setup)")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back to Home")
        }
    }
}
