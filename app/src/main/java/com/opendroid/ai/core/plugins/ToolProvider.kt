package com.opendroid.ai.core.plugins

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A single tool normalized for the OpenDroid agent. Providers must not be able to
 * grant themselves permissions; the app applies the local risk and consent policy.
 */
data class ToolDescriptor(
    val id: String,
    val name: String,
    val description: String,
    val inputSchema: JsonElement,
    val risk: ToolRisk,
    val requiresConfirmation: Boolean = risk >= ToolRisk.SENSITIVE,
    val providerId: String
)

enum class ToolRisk {
    READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE
}

sealed interface ToolResult {
    data class Success(val content: JsonElement) : ToolResult
    data class Failure(val message: String, val retryable: Boolean = false) : ToolResult
}

interface ToolProvider {
    val providerId: String
    suspend fun listTools(): List<ToolDescriptor>
    suspend fun invoke(toolId: String, arguments: JsonElement): ToolResult
}

/**
 * Validation deliberately fails closed. This minimal validator handles the core JSON
 * Schema subset commonly used by MCP tools. Unsupported schema keywords do not silently
 * grant permission; schemas requiring unsupported features should be rejected by callers.
 */
object ToolArgumentValidator {
    fun validate(schema: JsonElement, value: JsonElement): String? {
        val root = schema as? kotlinx.serialization.json.JsonObject
            ?: return "Tool schema must be a JSON object."
        val obj = value as? kotlinx.serialization.json.JsonObject
            ?: return "Tool arguments must be a JSON object."

        val allowedKeys = root["properties"] as? kotlinx.serialization.json.JsonObject
        val required = (root["required"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
        val allowAdditional = (root["additionalProperties"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toBooleanStrictOrNull() ?: true

        for (name in required) {
            if (obj[name] == null) return "Missing required argument: $name"
        }
        for ((name, item) in obj) {
            val propertySchema = allowedKeys?.get(name)
            if (propertySchema == null) {
                if (!allowAdditional) return "Unknown argument: $name"
                continue
            }
            val p = propertySchema as? kotlinx.serialization.json.JsonObject ?: continue
            val type = (p["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val matches = when (type) {
                "string" -> item is kotlinx.serialization.json.JsonPrimitive && item.isString
                "number" -> (item as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull != null
                "integer" -> (item as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull != null
                "boolean" -> (item as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull != null
                "object" -> item is kotlinx.serialization.json.JsonObject
                "array" -> item is kotlinx.serialization.json.JsonArray
                "null" -> item == kotlinx.serialization.json.JsonNull
                null -> true
                else -> false
            }
            if (!matches) return "Argument '$name' has an invalid type (expected $type)."
            val enumValues = p["enum"] as? kotlinx.serialization.json.JsonArray
            if (enumValues != null && enumValues.none { it == item }) {
                return "Argument '$name' is not an allowed value."
            }
        }
        return null
    }
}

/** Converts MCP-provided tool metadata into normalized local descriptors. */
object McpToolDescriptorFactory {
    fun fromServer(
        serverId: String,
        providerId: String,
        tool: kotlinx.serialization.json.JsonObject
    ): ToolDescriptor? {
        val name = (tool["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
        val description = (tool["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val schema = tool["inputSchema"] as? kotlinx.serialization.json.JsonObject
            ?: kotlinx.serialization.json.buildJsonObject { put("type", "object") }
        // MCP servers control their descriptions. Treat these as untrusted display text and
        // default all external tools to confirmation-required until a local policy classifies them.
        val normalized = name.lowercase()
        val risky = listOf("delete", "remove", "write", "send", "purchase", "pay", "shell", "exec", "run", "update")
            .any { normalized.contains(it) }
        return ToolDescriptor(
            id = "$serverId:$name",
            name = name,
            description = description.take(1000),
            inputSchema = schema,
            risk = if (risky) ToolRisk.DESTRUCTIVE else ToolRisk.SENSITIVE,
            requiresConfirmation = true,
            providerId = providerId
        )
    }
}
