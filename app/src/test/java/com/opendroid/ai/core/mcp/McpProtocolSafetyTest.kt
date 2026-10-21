package com.opendroid.ai.core.mcp

import com.opendroid.ai.core.plugins.TermuxCommandTemplate
import com.opendroid.ai.core.plugins.ToolArgumentValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class McpProtocolSafetyTest {
    private val json = Json

    @Test
    fun rejectsRemoteCleartextEndpoint() {
        val error = runCatching {
            java.net.URI("http://example.com/mcp").also { uri ->
                require(uri.scheme == "https" || (uri.scheme == "http" && uri.host == "localhost"))
            }
        }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun validatesRequiredTypesAndEnums() {
        val schema = json.parseToJsonElement(
            """{"type":"object","properties":{"mode":{"type":"string","enum":["read","write"]},"count":{"type":"integer"}},"required":["mode"],"additionalProperties":false}"""
        )
        assertNull(ToolArgumentValidator.validate(schema, json.parseToJsonElement("""{"mode":"read","count":2}""")))
        assertNotNull(ToolArgumentValidator.validate(schema, json.parseToJsonElement("""{"mode":"admin"}""")))
        assertNotNull(ToolArgumentValidator.validate(schema, json.parseToJsonElement("""{"count":"2"}""")))
        assertNotNull(ToolArgumentValidator.validate(schema, json.parseToJsonElement("""{"mode":"read","extra":true}""")))
    }

    @Test
    fun mutatingTermuxTemplateRequiresConfirmationAndNeverJoinsShell() {
        val template = TermuxCommandTemplate(
            id = "git.status",
            displayName = "Git status",
            executable = "git",
            fixedArguments = listOf("status", "--", "{path}"),
            allowedArgumentNames = setOf("path"),
            mutating = true
        )
        assertThrows(IllegalArgumentException::class.java) {
            template.buildArgv(mapOf("path" to "."), userConfirmed = false)
        }
        assertEquals(listOf("git", "status", "--", "repo with spaces"), template.buildArgv(
            mapOf("path" to "repo with spaces"), userConfirmed = true
        ))
    }

    @Test
    fun rejectsUnexpectedTemplateArguments() {
        val template = TermuxCommandTemplate(
            id = "whoami",
            displayName = "Who am I",
            executable = "whoami",
            fixedArguments = emptyList(),
            allowedArgumentNames = emptySet(),
            mutating = false
        )
        assertThrows(IllegalArgumentException::class.java) {
            template.buildArgv(mapOf("cmd" to "rm -rf /"), userConfirmed = true)
        }
    }
}
