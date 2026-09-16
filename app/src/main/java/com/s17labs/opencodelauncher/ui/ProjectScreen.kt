package com.s17labs.opencodelauncher.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Minimal folder browser over shared storage (Phase 5).
 * Shown only after all-files access is granted — without it there are no
 * real paths to bind-mount, so the caller shows the grant screen instead.
 */
@Composable
fun ProjectScreen(
    root: File,
    current: File?,
    onPick: (File) -> Unit,
    onGrantStorage: () -> Unit,
    hasAccess: Boolean,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Project folder", style = MaterialTheme.typography.headlineMedium)
        if (!hasAccess) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Files access needed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "OpenCode runs natively and needs a real folder path to work in. " +
                            "Grant all-files access on the next system screen (you can revoke it anytime).",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onGrantStorage, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant files access")
                    }
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("Back")
            }
            return
        }
        ProjectBrowser(root = root, current = current, onPick = onPick, onBack = onBack)
    }
}

@Composable
private fun ProjectBrowser(
    root: File,
    current: File?,
    onPick: (File) -> Unit,
    onBack: () -> Unit
) {
    var path by remember { mutableStateOf(current?.takeIf { it.exists() } ?: root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var readable by remember { mutableStateOf(true) }
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            val dir = path
            readable = dir.canRead()
            entries = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
    }

    // Column parent: lays the browser out top-to-bottom and provides the
    // ColumnScope that Modifier.weight (list takes remaining space) needs.
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(path.absolutePath, style = MaterialTheme.typography.bodySmall)
        if (!readable) {
            Text("Cannot read this folder.", style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { path.parentFile?.let { path = it } },
                enabled = path.absolutePath != root.absolutePath && path.parentFile != null,
                modifier = Modifier.weight(1f)
            ) { Text("Up") }
            Button(onClick = { onPick(path) }, modifier = Modifier.weight(2f)) {
                Text("Use this folder")
            }
        }
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("New folder name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val name = newName.trim()
                if (name.isNotEmpty() && !name.contains("/")) {
                    val created = File(path, name)
                    if (created.mkdirs() || created.isDirectory) {
                        newName = ""
                        path = created
                    }
                }
            },
            enabled = newName.trim().isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Create + open") }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(entries, key = { it.absolutePath }) { dir ->
                Card(modifier = Modifier.fillMaxWidth().clickable { path = dir }) {
                    Text(
                        "📁 " + dir.name,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}
