package com.opendroid.ai.core.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TermuxBridgeResult {
    data class Dispatched(val commandLabel: String) : TermuxBridgeResult
    data class Failure(val message: String) : TermuxBridgeResult
}

/**
 * Thin adapter for Termux's documented RUN_COMMAND interface.
 *
 * This sends fixed argv values to Termux; it does not invoke a shell in OpenDroid.
 * Result capture needs the documented Termux PendingIntent/result receiver protocol and is not
 * silently simulated here, so the current adapter reports dispatch acceptance only.
 */
@Singleton
class TermuxRunCommandBridge @Inject constructor(
    private val context: Context
) {
    suspend fun dispatch(
        template: TermuxCommandTemplate,
        arguments: Map<String, String>,
        userConfirmed: Boolean,
        workDirectory: String = "~/"
    ): TermuxBridgeResult = withContext(Dispatchers.IO) {
        try {
            val argv = template.buildArgv(arguments, userConfirmed)
            if (argv.isEmpty()) return@withContext TermuxBridgeResult.Failure("Empty command.")
            val packageInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo("com.termux", PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo("com.termux", 0)
                }
            } catch (_: Exception) {
                return@withContext TermuxBridgeResult.Failure("Termux is not installed or is not visible to OpenDroid.")
            }
            if (packageInfo.packageName != "com.termux") {
                return@withContext TermuxBridgeResult.Failure("Unexpected Termux package.")
            }

            // Termux expects an absolute executable path or its documented ~/ / $PREFIX/ form.
            val commandPath = argv.first()
            if (!commandPath.startsWith("/") && !commandPath.startsWith("~/") && !commandPath.startsWith("${'$'}PREFIX/")) {
                return@withContext TermuxBridgeResult.Failure(
                    "Command must use an absolute path, ~/ path, or ${'$'}PREFIX/ path. Resolve the executable in a reviewed template."
                )
            }
            require(workDirectory.length <= 512 && workDirectory.none { it.code == 0 || it == '\n' || it == '\r' }) {
                "Working directory is invalid."
            }
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", commandPath)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", argv.drop(1).toTypedArray())
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workDirectory)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (template.mutating && !userConfirmed) {
                return@withContext TermuxBridgeResult.Failure("This command needs user confirmation.")
            }
            try {
                context.startService(intent)
            } catch (_: SecurityException) {
                return@withContext TermuxBridgeResult.Failure(
                    "Termux denied this request. Grant OpenDroid the RUN_COMMAND permission and enable allow-external-apps in Termux settings."
                )
            } catch (_: Exception) {
                return@withContext TermuxBridgeResult.Failure("Unable to dispatch command to Termux.")
            }
            TermuxBridgeResult.Dispatched(template.displayName)
        } catch (e: Exception) {
            TermuxBridgeResult.Failure(e.message?.take(200) ?: "Termux command rejected.")
        }
    }
}
