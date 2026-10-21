package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.mcp.McpConnectionResult
import com.opendroid.ai.core.mcp.McpHttpClient
import com.opendroid.ai.core.mcp.McpSavedServer
import com.opendroid.ai.core.mcp.McpServerRepository
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
    private val repository: McpServerRepository
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
                        withContext(Dispatchers.IO) {
                            repository.save(name.trim(), endpoint.trim(), tools)
                        }
                        _status.value = "Connection successful. Server metadata saved. The bearer token was not saved; reconnecting to authenticated servers will require entering it again."
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
                when (val result = client.listTools(server.endpoint)) {
                    is McpConnectionResult.Success -> {
                        val array = result.payload.optJSONObject("result")?.optJSONArray("tools")
                        if (array == null) {
                            _status.value = "The server did not return a tools array."
                        } else {
                            val tools = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                            withContext(Dispatchers.IO) { repository.replaceTools(id, tools) }
                            _status.value = "Tool list refreshed. If this server requires authentication, provide a token in the server settings; tokens are not persisted by this first increment."
                        }
                    }
                    is McpConnectionResult.Failure -> _status.value = result.message
                    is McpConnectionResult.Connected -> _status.value = "Unexpected connection response."
                }
            } catch (e: Exception) {
                _status.value = e.message?.take(240) ?: "Unable to refresh tools."
            } finally {
                _checking.value = false
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.remove(id) }
                .onSuccess { _status.value = "MCP server removed." }
                .onFailure { _status.value = "Unable to remove MCP server metadata." }
        }
    }
}
