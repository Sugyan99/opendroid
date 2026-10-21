package com.opendroid.ai.core.security

/**
 * Small security boundary for MCP bearer tokens. Implementations must use Android Keystore-backed
 * encrypted storage. Token IDs are stable logical identifiers and never contain the token.
 */
interface McpCredentialStore {
    fun read(serverId: String): McpCredentialStoreResult
    fun write(serverId: String, token: String): McpCredentialStoreResult
    fun remove(serverId: String): McpCredentialStoreResult
}

sealed interface McpCredentialStoreResult {
    data class Success(val token: String? = null) : McpCredentialStoreResult
    data object CredentialsMustBeReentered : McpCredentialStoreResult
    data object StorageUnavailable : McpCredentialStoreResult
}
