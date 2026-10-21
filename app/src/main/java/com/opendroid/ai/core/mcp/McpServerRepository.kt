package com.opendroid.ai.core.mcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class McpSavedTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String
)

data class McpSavedServer(
    val id: String,
    val name: String,
    val endpoint: String,
    val tools: List<McpSavedTool>,
    val approvedToolNames: Set<String> = emptySet(),
    val enabled: Boolean = false
)

/**
 * Keeps only non-secret profile metadata in preferences. Bearer credentials are deliberately
 * never accepted by this repository; use the Keystore-backed credential store for secrets.
 */
@Singleton
class McpServerRepository @Inject constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("opendroid_mcp_servers", Context.MODE_PRIVATE)
    private val mutableServers = MutableStateFlow(readAll())
    val servers: StateFlow<List<McpSavedServer>> = mutableServers

    @Synchronized
    fun save(name: String, endpoint: String, tools: List<JSONObject>): McpSavedServer {
        require(name.isNotBlank() && name.length <= 80) { "Server name is required (maximum 80 characters)." }
        require(endpoint.length <= 2048) { "Endpoint URL is too long." }
        val uri = java.net.URI(endpoint)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]"))) {
            "Use HTTPS, or an explicit localhost URL for development."
        }
        require(uri.host != null && uri.userInfo == null) { "Endpoint URL must include a host and must not embed credentials." }
        val id = UUID.randomUUID().toString()
        val converted = tools.mapNotNull { obj ->
            val toolName = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpSavedTool(
                name = toolName.take(256),
                description = obj.optString("description", "").take(1000),
                inputSchemaJson = obj.optJSONObject("inputSchema")?.toString() ?: "{}"
            )
        }
        val server = McpSavedServer(id, name.trim(), endpoint, converted)
        val updated = (mutableServers.value + server)
        check(prefs.edit().putString(KEY_SERVERS, encode(updated)).commit()) {
            "Unable to save MCP server metadata."
        }
        mutableServers.value = updated
        return server
    }

    @Synchronized
    fun remove(id: String) {
        val updated = mutableServers.value.filterNot { it.id == id }
        check(prefs.edit().putString(KEY_SERVERS, encode(updated)).commit()) {
            "Unable to remove MCP server metadata."
        }
        mutableServers.value = updated
    }

    fun setEnabled(id: String, enabled: Boolean) {
        update(id) { it.copy(enabled = enabled) }
    }

    fun setToolApproved(id: String, toolName: String, approved: Boolean) {
        update(id) { server ->
            val names = if (approved) server.approvedToolNames + toolName else server.approvedToolNames - toolName
            server.copy(approvedToolNames = names)
        }
    }

    private fun update(id: String, transform: (McpSavedServer) -> McpSavedServer) {
        val updated = mutableServers.value.map { if (it.id == id) transform(it) else it }
        check(prefs.edit().putString(KEY_SERVERS, encode(updated)).commit()) {
            "Unable to update MCP server permissions."
        }
        mutableServers.value = updated
    }

    fun get(id: String): McpSavedServer? = mutableServers.value.firstOrNull { it.id == id }

    @Synchronized
    fun replaceTools(id: String, tools: List<JSONObject>) {
        val current = get(id) ?: return
        val converted = tools.mapNotNull { obj ->
            val toolName = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpSavedTool(toolName.take(256), obj.optString("description", "").take(1000), obj.optJSONObject("inputSchema")?.toString() ?: "{}")
        }
        val availableNames = converted.map { it.name }.toSet()
        // A server's tool listing may change. Remove approvals for tools no longer offered.
        val updated = mutableServers.value.map {
            if (it.id == id) it.copy(
                tools = converted,
                approvedToolNames = it.approvedToolNames intersect availableNames
            ) else it
        }
        check(prefs.edit().putString(KEY_SERVERS, encode(updated)).commit()) {
            "Unable to update MCP server metadata."
        }
        mutableServers.value = updated
    }

    private fun readAll(): List<McpSavedServer> = runCatching {
        val json = JSONArray(prefs.getString(KEY_SERVERS, "[]"))
        (0 until json.length()).mapNotNull { index ->
            val obj = json.optJSONObject(index) ?: return@mapNotNull null
            val toolsJson = obj.optJSONArray("tools") ?: JSONArray()
            val tools = (0 until toolsJson.length()).mapNotNull { i ->
                val item = toolsJson.optJSONObject(i) ?: return@mapNotNull null
                McpSavedTool(item.optString("name"), item.optString("description"), item.optString("inputSchema", "{}"))
            }
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val endpoint = obj.optString("endpoint").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpSavedServer(
                id, name, endpoint, tools,
                approvedToolNames = buildSet {
                    val approvals = obj.optJSONArray("approvedToolNames") ?: JSONArray()
                    for (approvalIndex in 0 until approvals.length()) {
                        approvals.optString(approvalIndex).takeIf { it.isNotBlank() }?.let(::add)
                    }
                },
                enabled = obj.optBoolean("enabled", false)
            )
        }
    }.getOrDefault(emptyList())

    private fun encode(servers: List<McpSavedServer>): String {
        val root = JSONArray()
        servers.forEach { server ->
            val tools = JSONArray()
            server.tools.forEach { tool ->
                tools.put(JSONObject().put("name", tool.name).put("description", tool.description).put("inputSchema", tool.inputSchemaJson))
            }
            val approvals = JSONArray()
            server.approvedToolNames.forEach { approvals.put(it) }
            root.put(JSONObject().put("id", server.id).put("name", server.name).put("endpoint", server.endpoint)
                .put("tools", tools).put("approvedToolNames", approvals).put("enabled", server.enabled))
        }
        return root.toString()
    }

    private companion object {
        const val KEY_SERVERS = "profiles_json"
    }
}
