package dev.minios.ocremote.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.ServerConfig

/**
 * Parse and validate a server URL string.
 * Accepts formats like:
 *   http://192.168.0.10:4096
 *   https://192.168.0.10
 *   https://my-server.example.com:4848
 *   192.168.0.10:4096           -> defaults to http://
 *   192.168.0.10                -> defaults to http://
 *
 * Returns the normalized URL (with scheme) or null if invalid.
 */
private fun validateAndNormalizeUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    // Add scheme if missing
    val withScheme = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        "http://$trimmed"
    } else {
        trimmed
    }

    return try {
        val url = java.net.URL(withScheme)
        // Must have a host
        if (url.host.isNullOrBlank()) return null
        // Port must be valid if specified
        if (url.port != -1 && url.port !in 1..65535) return null
        // Rebuild a clean URL (scheme + host + optional port)
        val port = url.port
        if (port != -1) {
            "${url.protocol}://${url.host}:$port"
        } else {
            "${url.protocol}://${url.host}"
        }
    } catch (e: Exception) {
        null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDialog(
    server: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String) -> Unit
) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.url ?: "http://") }
    var username by remember { mutableStateOf(server?.username ?: "opencode") }
    var password by remember { mutableStateOf(server?.password ?: "") }

    var nameError by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }

    val urlRequiredText = stringResource(R.string.server_url)
    val urlInvalidText = stringResource(R.string.server_invalid_url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = if (server != null) stringResource(R.string.home_edit) else stringResource(R.string.server_add))
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        nameError = it.isBlank()
                    },
                    label = { Text(stringResource(R.string.server_name)) },
                    placeholder = { Text(stringResource(R.string.server_name_hint)) },
                    isError = nameError,
                    supportingText = if (nameError) {
                        { Text(stringResource(R.string.server_name_required)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        urlError = null
                    },
                    label = { Text(stringResource(R.string.server_url)) },
                    placeholder = { Text(stringResource(R.string.server_url_hint)) },
                    isError = urlError != null,
                    supportingText = if (urlError != null) {
                        { Text(urlError!!) }
                    } else {
                        { Text(stringResource(R.string.server_url_example)) }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.server_username)) },
                    placeholder = { Text(stringResource(R.string.server_username_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.server_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Validate inputs
                    nameError = name.isBlank()
                    val normalizedUrl = validateAndNormalizeUrl(url)
                    urlError = when {
                        url.isBlank() -> urlRequiredText
                        normalizedUrl == null -> urlInvalidText
                        else -> null
                    }

                    if (!nameError && urlError == null && normalizedUrl != null) {
                        onSave(
                            name,
                            normalizedUrl,
                            username.ifBlank { "opencode" },
                            password
                        )
                    }
                }
            ) {
                Text(stringResource(R.string.server_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.server_cancel))
            }
        }
    )
}
