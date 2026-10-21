package com.opendroid.ai.core.security

import android.content.Context
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Android Keystore-backed storage for MCP bearer credentials using the existing AES-GCM record
 * infrastructure. Secrets are never included in profile JSON, logs, or tool descriptions.
 */
class AndroidMcpCredentialStore(context: Context) : McpCredentialStore {
    private val records = KeystoreSecretRecords(
        storage = SharedPreferencesSecretRecordStorage(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        ),
        cipher = AndroidKeyStoreAeadCipher(KEY_ALIAS)
    )

    override fun read(serverId: String): CredentialStoreResult =
        when (val result = records.read(storageKey(serverId), aad(serverId))) {
            is SecretRecordResult.Success -> CredentialStoreResult.Success(
                result.value?.let { String(it, StandardCharsets.UTF_8) }
            )
            SecretRecordResult.Unrecoverable -> CredentialStoreResult.CredentialsMustBeReentered
            SecretRecordResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
        }

    override fun write(serverId: String, token: String): CredentialStoreResult {
        requireServerId(serverId)
        if (token.length > MAX_TOKEN_CHARS || token.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            return CredentialStoreResult.StorageUnavailable
        }
        return when (records.write(
            storageKey(serverId),
            aad(serverId),
            token.toByteArray(StandardCharsets.UTF_8)
        )) {
            is SecretRecordResult.Success -> CredentialStoreResult.Success
            SecretRecordResult.Unrecoverable -> CredentialStoreResult.CredentialsMustBeReentered
            SecretRecordResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
        }
    }

    override fun remove(serverId: String): CredentialStoreResult =
        when (records.removeRecord(storageKey(serverId))) {
            is SecretRecordResult.Success -> CredentialStoreResult.Success
            SecretRecordResult.Unrecoverable -> CredentialStoreResult.CredentialsMustBeReentered
            SecretRecordResult.StorageUnavailable -> CredentialStoreResult.StorageUnavailable
        }

    private fun storageKey(serverId: String): String {
        requireServerId(serverId)
        val encoded = Base64.encodeToString(serverId.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "mcp-token.$encoded"
    }

    private fun aad(serverId: String): String = "opendroid:mcp:$serverId"

    private fun requireServerId(serverId: String) {
        require(serverId.matches(Regex("[a-zA-Z0-9._-]{1,100}"))) { "Invalid MCP server ID." }
    }

    companion object {
        private const val PREFERENCES_NAME = "opendroid_mcp_credentials"
        private const val KEY_ALIAS = "opendroid.mcp_credentials.aes_gcm.v1"
        private const val MAX_TOKEN_CHARS = 8192
    }
}
