package com.opendroid.ai.core.security

/**
 * Small security boundary for MCP bearer tokens. Implementations must use Android Keystore-backed
 * encrypted storage. Token IDs are stable logical identifiers and never contain the token.
 */
interface McpCredentialStore {
    fun read(serverId: String): CredentialStoreResult
    fun write(serverId: String, token: String): CredentialStoreResult
    fun remove(serverId: String): CredentialStoreResult
}
