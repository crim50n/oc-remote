package dev.minios.ocremote.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalServerManager"

@Singleton
class LocalServerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val LOCAL_SERVER_URL = "http://127.0.0.1:4096"
        const val DEFAULT_NO_PROXY_LIST = "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16"

        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

        private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"

        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private const val START_SCRIPT = "$TERMUX_HOME/opencode-local/start.sh"
        private const val STOP_SCRIPT = "$TERMUX_HOME/opencode-local/stop.sh"

        private const val SETUP_SCRIPT_URL =
            "https://raw.githubusercontent.com/crim50n/oc-remote/master/scripts/opencode-local-setup.sh"
    }

    /** One-liner the user pastes into Termux to install everything. */
    fun getSetupCommand(): String =
        "curl -fsSL -H \"Cache-Control: no-cache\" -H \"Pragma: no-cache\" \"$SETUP_SCRIPT_URL?ts=\$(date +%s)\" | bash"

    fun isTermuxInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    TERMUX_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Launch Termux main activity so the user can paste the setup command. */
    fun openTermux(callerContext: Context): Boolean {
        return try {
            val intent = callerContext.packageManager
                .getLaunchIntentForPackage(TERMUX_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                callerContext.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Termux", e)
            false
        }
    }

    suspend fun isServerHealthy(): Boolean = withContext(Dispatchers.IO) {
        val healthUrl = "$LOCAL_SERVER_URL/global/health"
        return@withContext try {
            val conn = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2000
                readTimeout = 2000
            }
            conn.inputStream.bufferedReader().use { reader ->
                conn.responseCode in 200..299 && reader.readText().contains("\"healthy\":true")
            }
        } catch (_: Exception) {
            false
        }
    }

    fun startServer(
        callerContext: Context,
        proxyUrl: String? = null,
        noProxyList: String? = null,
        hostName: String? = null,
        serverPassword: String? = null,
    ): Result<Unit> {
        return runCatching {
            check(isTermuxInstalled()) { "Termux is not installed" }
            val args = buildList {
                if (!hostName.isNullOrBlank()) {
                    add("--hostname")
                    add(hostName)
                }

                if (!serverPassword.isNullOrBlank()) {
                    add("--server-password")
                    add(serverPassword)
                }

                if (!proxyUrl.isNullOrBlank()) {
                    add("--proxy")
                    add(proxyUrl)
                }

                val normalizedNoProxy = noProxyList
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(",")
                    ?: DEFAULT_NO_PROXY_LIST

                if (normalizedNoProxy.isNotBlank()) {
                    add("--no-proxy")
                    add(normalizedNoProxy)
                }
            }
            val intent = buildRunCommandIntent(
                commandPath = START_SCRIPT,
                args = args.toTypedArray(),
                // Run fully in background so start does not require opening Termux UI/tab.
                background = true,
                sessionAction = "0",
            )
            startRunCommandService(callerContext, intent)
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to start local server", e)
        }
    }

    fun stopServer(callerContext: Context): Result<Unit> {
        return runCatching {
            check(isTermuxInstalled()) { "Termux is not installed" }
            val intent = buildRunCommandIntent(
                commandPath = STOP_SCRIPT,
                args = emptyArray(),
                background = true,
                sessionAction = "0",
            )
            startRunCommandService(callerContext, intent)
            Unit
        }.onFailure { e ->
            Log.e(TAG, "Failed to stop local server", e)
        }
    }

    private fun startRunCommandService(callerContext: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callerContext.startForegroundService(intent)
        } else {
            callerContext.startService(intent)
        }
    }

    private fun buildRunCommandIntent(
        commandPath: String,
        args: Array<String>,
        background: Boolean,
        sessionAction: String,
    ): Intent {
        return Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_RUN_COMMAND_SERVICE)
            action = TERMUX_RUN_COMMAND_ACTION
            putExtra(EXTRA_COMMAND_PATH, commandPath)
            putExtra(EXTRA_ARGUMENTS, args)
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, background)
            putExtra(EXTRA_SESSION_ACTION, sessionAction)
        }
    }
}
