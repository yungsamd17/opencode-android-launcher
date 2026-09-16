package com.s17labs.opencodelauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 5 (git auth): install `gh`, then log in via the device-code flow.
 * No token typing, no custom OAuth — the app shows gh's one-time code plus
 * a browser link, exactly like the opencode web handoff.
 */
@Composable
fun GitScreen(
    ghStatus: String,
    authed: Boolean,
    busyLabel: String?,
    deviceCode: String?,
    deviceUrl: String?,
    onInstallGh: () -> Unit,
    onConnect: () -> Unit,
    onOpenDeviceUrl: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit
) {
    val busy = busyLabel != null
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("GitHub", style = MaterialTheme.typography.headlineMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ghStatus, style = MaterialTheme.typography.bodyMedium)
                if (busy) {
                    Text(busyLabel, style = MaterialTheme.typography.bodySmall)
                    CircularProgressIndicator()
                }
            }
        }
        if (deviceCode != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Approve in your browser", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Open the link, sign in to GitHub, and enter this code. " +
                            "The login here completes on its own (up to a few minutes).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(deviceCode, style = MaterialTheme.typography.headlineSmall)
                    Button(
                        onClick = onOpenDeviceUrl,
                        enabled = deviceUrl != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open github.com/login/device")
                    }
                }
            }
        }
        Button(onClick = onInstallGh, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Install GitHub CLI")
        }
        Button(onClick = onConnect, enabled = !busy && !authed, modifier = Modifier.fillMaxWidth()) {
            Text("Connect GitHub account")
        }
        if (authed) {
            OutlinedButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Log out")
            }
        }
        Text(
            "Credentials stay in the Linux runtime on this device. " +
                "Once connected, opencode's agent runs git itself — clone, commit, push.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
