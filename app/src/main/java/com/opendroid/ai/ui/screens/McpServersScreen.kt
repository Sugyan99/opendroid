package com.opendroid.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opendroid.ai.ui.viewmodel.McpServersViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersScreen(
    onBack: () -> Unit,
    viewModel: McpServersViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP integrations") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Connect external tools through an MCP server. The server must already be running and reachable. " +
                    "HTTPS is required except for explicit localhost development endpoints. Standard stdio servers need a configured bridge."
            )
            Text(
                "This screen discovers and reviews tools; agent-side execution is not enabled yet. Approving a tool here records your choice but does not run it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Add a server", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(endpoint, { endpoint = it.take(2048) }, label = { Text("MCP endpoint URL (HTTPS)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        token,
                        { token = it },
                        label = { Text("Bearer token (optional; stored encrypted)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = { viewModel.testAndSave(name, endpoint, token) },
                        enabled = !checking && name.isNotBlank() && endpoint.isNotBlank()
                    ) {
                        if (checking) CircularProgressIndicator()
                        else Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(Modifier.height(2.dp))
                        Text(if (checking) "Testing…" else "Test and add")
                    }
                    Text("Bearer credentials are stored with Android Keystore-backed encryption. Tools are listed only after a successful protocol handshake.")
                }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            Text("Configured servers", style = MaterialTheme.typography.titleMedium)
            if (servers.isEmpty()) {
                Text("No MCP servers have been saved.")
            } else {
                servers.forEach { server ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall)
                            Text(server.endpoint, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Enable server tools")
                                Switch(
                                    checked = server.enabled,
                                    onCheckedChange = { viewModel.setServerEnabled(server.id, it) }
                                )
                            }
                            Text("Discovered tools: ${server.tools.size}")
                            Text(
                                "Review each tool before enabling it. Approved tools may be exposed to the agent only after provider integration and local risk checks.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            server.tools.forEach { tool ->
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(
                                        checked = tool.name in server.approvedToolNames,
                                        onCheckedChange = { approved -> viewModel.setToolApproved(server.id, tool.name, approved) }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(tool.description.take(180), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { viewModel.refresh(server.id) }, enabled = !checking) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Text("Refresh")
                                }
                                Button(onClick = { viewModel.remove(server.id) }) { Text("Remove") }
                            }
                        }
                    }
                }
            }
            Text(
                "MCP tool results are untrusted. Review tool descriptions before using them. " +
                    "Do not connect servers you do not trust or paste long-lived secrets into untrusted services.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
