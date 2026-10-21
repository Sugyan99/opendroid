package com.opendroid.ai.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimal MCP Streamable HTTP client.
 *
 * Supported exchange shape: JSON-RPC 2.0 requests/responses using application/json.
 * Server-Sent Events responses are intentionally rejected with a clear error in this first
 * increment; implementations must not pretend an unparsed SSE stream is a tool result.
 */
@Singleton
class McpHttpClient @Inject constructor(
    private val http: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }
    private val requestIds = AtomicLong(1)
    private val sessionIds = ConcurrentHashMap<String, String>()

    suspend fun testConnection(endpoint: String, bearerToken: String? = null): McpConnectionResult {
        return try {
            validateEndpoint(endpoint)
            val initialized = request(
                endpoint = endpoint,
                method = "initialize",
                params = buildJsonObject {
                    put("protocolVersion", SUPPORTED_PROTOCOL_VERSION)
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject {
                        put("name", "OpenDroid")
                        put("version", "1.0")
                    })
                },
                bearerToken = bearerToken
            )
            if (initialized is McpConnectionResult.Failure) return initialized
            val initializePayload = (initialized as? McpConnectionResult.Success)?.payload?.get("result") as? JsonObject
                ?: return McpConnectionResult.Failure("MCP server returned no initialization result.")
            val negotiatedVersion = (initializePayload["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            if (negotiatedVersion != SUPPORTED_PROTOCOL_VERSION) {
                sessionIds.remove(endpoint)
                return McpConnectionResult.Failure(
                    "MCP protocol version is not supported by this client. Expected $SUPPORTED_PROTOCOL_VERSION."
                )
            }
            val notification = postNotification(
                endpoint,
                "notifications/initialized",
                bearerToken
            )
            if (notification != null) return McpConnectionResult.Failure(notification)
            val tools = request(endpoint, "tools/list", buildJsonObject {}, bearerToken)
            when (tools) {
                is McpConnectionResult.Success -> {
                    val result = tools.payload["result"] as? JsonObject
                        ?: return McpConnectionResult.Failure("MCP server returned no result object.")
                    val entries = result["tools"] as? JsonArray
                        ?: return McpConnectionResult.Failure("MCP server did not return a tools array.")
                    McpConnectionResult.Connected(
                        serverInfo = initializePayload["serverInfo"],
                        tools = entries.mapNotNull { it as? JsonObject }
                    )
                }
                is McpConnectionResult.Failure -> tools
                else -> McpConnectionResult.Failure("Unexpected MCP response.")
            }
        } catch (e: Exception) {
            McpConnectionResult.Failure(safeMessage(e))
        }
    }

    suspend fun listTools(endpoint: String, bearerToken: String? = null): McpConnectionResult =
        request(endpoint, "tools/list", buildJsonObject {}, bearerToken)

    suspend fun callTool(
        endpoint: String,
        toolName: String,
        arguments: JsonObject,
        bearerToken: String? = null
    ): McpConnectionResult {
        if (toolName.isBlank() || toolName.length > 256) {
            return McpConnectionResult.Failure("Tool name is invalid.")
        }
        return request(endpoint, "tools/call", buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }, bearerToken)
    }

    private suspend fun request(
        endpoint: String,
        method: String,
        params: JsonObject,
        bearerToken: String?
    ): McpConnectionResult = withContext(Dispatchers.IO) {
        validateEndpoint(endpoint)
        val id = requestIds.getAndIncrement()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", SUPPORTED_PROTOCOL_VERSION)
            .apply { sessionIds[endpoint]?.let { header("MCP-Session-Id", it) } }
            .post(body)
        if (!bearerToken.isNullOrBlank()) requestBuilder.header("Authorization", "Bearer $bearerToken")

        try {
            http.newCall(requestBuilder.build()).execute().use { response ->
                response.header("MCP-Session-Id")?.let { sessionIds[endpoint] = it }
                if (!response.isSuccessful) {
                    return@withContext McpConnectionResult.Failure("MCP HTTP error: ${response.code}.")
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.lowercase().contains("application/json")) {
                    return@withContext McpConnectionResult.Failure(
                        "MCP server response type is unsupported. This client currently requires application/json; SSE is not supported yet."
                    )
                }
                val source = response.body?.source()
                    ?: return@withContext McpConnectionResult.Failure("MCP server returned an empty response.")
                source.request(MAX_RESPONSE_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES) {
                    return@withContext McpConnectionResult.Failure("MCP response exceeded the 1 MiB safety limit.")
                }
                val responseText = source.buffer.readUtf8()
                val payload = try {
                    json.parseToJsonElement(responseText).jsonObject
                } catch (_: Exception) {
                    return@withContext McpConnectionResult.Failure("MCP server returned malformed JSON.")
                }
                if (payload["jsonrpc"] != JsonPrimitive("2.0")) {
                    return@withContext McpConnectionResult.Failure("Invalid JSON-RPC version in MCP response.")
                }
                if (payload["id"] != JsonPrimitive(id)) {
                    return@withContext McpConnectionResult.Failure("MCP response ID did not match the request.")
                }
                if (payload["error"] != null && payload["error"] != JsonNull) {
                    val error = payload["error"] as? JsonObject
                    val code = error?.get("code")?.toString().orEmpty()
                    val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
                        ?: "Unknown protocol error"
                    return@withContext McpConnectionResult.Failure("MCP protocol error $code: ${message.take(240)}")
                }
                McpConnectionResult.Success(payload)
            }
        } catch (e: IOException) {
            McpConnectionResult.Failure(safeMessage(e))
        } catch (e: Exception) {
            McpConnectionResult.Failure(safeMessage(e))
        }
    }

    private suspend fun postNotification(
        endpoint: String,
        method: String,
        bearerToken: String?
    ): String? = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .header("MCP-Protocol-Version", SUPPORTED_PROTOCOL_VERSION)
            .apply { sessionIds[endpoint]?.let { header("MCP-Session-Id", it) } }
            .apply {
                if (!bearerToken.isNullOrBlank()) header("Authorization", "Bearer $bearerToken")
            }
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { response ->
                response.header("MCP-Session-Id")?.let { sessionIds[endpoint] = it }
                // Streamable HTTP notifications may legitimately return 202 without a body.
                if (response.code == 202 || response.code == 204 || response.isSuccessful) null
                else "MCP initialized notification failed with HTTP ${response.code}."
            }
        } catch (e: Exception) {
            safeMessage(e)
        }
    }

    private fun validateEndpoint(endpoint: String) {
        require(endpoint.length <= 2048) { "MCP endpoint is too long." }
        val uri = URI(endpoint)
        require(uri.scheme == "https" || isExplicitLoopbackHttp(uri)) {
            "MCP endpoints must use HTTPS. Plain HTTP is allowed only for localhost/127.0.0.1/[::1] during local development."
        }
        require(uri.userInfo == null) { "MCP endpoint must not embed credentials in the URL." }
        require(uri.host != null && uri.host.isNotBlank()) { "MCP endpoint must include a host." }
    }

    private fun isExplicitLoopbackHttp(uri: URI): Boolean =
        uri.scheme == "http" && uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")

    private fun safeMessage(error: Exception): String {
        // Avoid returning request URLs, headers or token-bearing exception text to logs/UI.
        return when (error) {
            is java.net.SocketTimeoutException -> "MCP request timed out."
            is IOException -> "Unable to reach the MCP server. Check its URL and network access."
            else -> error.message?.takeIf { it.length <= 240 && !it.contains("Bearer", ignoreCase = true) }
                ?: "MCP request failed."
        }
    }

    companion object {
        const val SUPPORTED_PROTOCOL_VERSION = "2025-06-18"
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

sealed interface McpConnectionResult {
    data class Success(val payload: JsonObject) : McpConnectionResult
    data class Connected(
        val serverInfo: JsonElement?,
        val tools: List<JsonObject>
    ) : McpConnectionResult
    data class Failure(val message: String) : McpConnectionResult
}
