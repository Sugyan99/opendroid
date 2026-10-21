package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.mcp.McpConnectionResult
import com.opendroid.ai.core.mcp.McpHttpClient
import com.opendroid.ai.core.mcp.McpSavedServer
import com.opendroid.ai.core.mcp.McpServerRepository
import com.opendroid.ai.core.security.McpCredentialStore
import com.opendroid.ai.core.security.McpCredentialStoreResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class McpServersViewModel @Inject constructor(
    private val client: McpHttpClient,
    private val repository: McpServerRepository,
    private val credentialStore: McpCredentialStore
) : ViewModel() {
    val servers: StateFlow<List<McpSavedServer>> = repository.servers
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    fun testAndSave(name: String, endpoint: String, bearerToken: String) {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            _status.value = null
            try {
                val result = client.testConnection(endpoint.trim(), bearerToken.takeIf { it.isNotBlank() })
                when (result) {
                    is McpConnectionResult.Connected -> {
                        val tools = result.tools.map { JSONObject(it.toString()) }
                        val saved = withContext(Dispatchers.IO) {
                            repository.save(name.trim(), endpoint.trim(), tools)
                        }
                        if (!bearerToken.isNullOrBlank()) {
                            when (credentialStore.write(saved.id, bearerToken)) {
                                is McpCredentialStoreResult.Success -> Unit
                                McpCredentialStoreResult.CredentialsMustBeReentered,
                                McpCredentialStoreResult.StorageUnavailable -> {
                                    withContext(Dispatchers.IO) { repository.remove(saved.id) }
                                    _status.value = "Server token could not be stored securely. No server profile was kept."
                                    return@launch
                                }
                            }
                        }
                        _status.value = "Connection successful. Server metadata saved. Bearer credentials are stored using Android Keystore-backed encryption."
                    }
                    is McpConnectionResult.Failure -> _status.value = result.message
                    is McpConnectionResult.Success -> _status.value = "Connected, but the server response did not include a supported tool list."
                }
            } catch (e: Exception) {
                _status.value = e.message?.take(240) ?: "Unable to save MCP server."
            } finally {
                _checking.value = false
            }
        }
    }

    fun refresh(id: String) {
        if (_checking.value) return
        val server = repository.get(id) ?: return
        viewModelScope.launch {
            _checking.value = true
            _status.value = "Refreshing tools for ${server.name}…"
            try {
                val token = (credentialStore.read(server.id) as? McpCredentialStoreResult.Success)?.token
                when (val result = client.testConnection(server.endpoint, token)) {
                    is McpConnectionResult.Connected -> {
                        val tools = result.tools.map { JSONObject(it.toString()) }
                        withContext(Dispatchers.IO) { repository.replaceTools(id, tools) }
                        _status.value = "Connection verified and tool list refreshed."
                    }
                    is McpConnectionResult.Failure -> _status.value = result.message
                    is McpConnectionResult.Success -> _status.value = "Connected, but the server did not return a usable tool list."
                }
            } catch (e: Exception) {
                _status.value = e.message?.take(240) ?: "Unable to refresh tools."
            } finally {
                _checking.value = false
            }
        }
    }

    fun setServerEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setEnabled(id, enabled) }
                .onSuccess {
                    _status.value = if (enabled) {
                        "Server enabled. Tool execution is still gated until the agent adapter is connected."
                    } else {
                        "Server disabled."
                    }
                }
                .onFailure { _status.value = "Unable to update server state." }
        }
    }

    fun setToolApproved(id: String, toolName: String, approved: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setToolApproved(id, toolName, approved) }
                .onSuccess {
                    _status.value = if (approved) {
                        "Tool reviewed and approved for future agent integration: $toolName"
                    } else {
                        "Tool approval revoked: $toolName"
                    }
                }
                .onFailure { _status.value = "Unable to update tool approval." }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.remove(id)
                credentialStore.remove(id)
            }
                .onSuccess { _status.value = "MCP server and its stored credential removed." }
                .onFailure { _status.value = "Unable to remove MCP server metadata or credentials." }
        }
    }
}
