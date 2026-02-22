package dev.minios.ocremote.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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

private fun deriveServerNameFromUrl(normalizedUrl: String): String {
    return try {
        val url = java.net.URL(normalizedUrl)
        val host = url.host
        val port = url.port
        if (port != -1) "$host:$port" else host
    } catch (_: Exception) {
        normalizedUrl
            .removePrefix("http://")
            .removePrefix("https://")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDialog(
    server: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, username: String, password: String, autoConnect: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var url by remember { mutableStateOf(server?.url ?: "http://") }
    var username by remember { mutableStateOf(server?.username ?: "opencode") }
    var password by remember { mutableStateOf(server?.password ?: "") }
    var autoConnect by remember { mutableStateOf(server?.autoConnect ?: false) }

    var urlError by remember { mutableStateOf<String?>(null) }

    val urlRequiredText = stringResource(R.string.server_url)
    val urlInvalidText = stringResource(R.string.server_invalid_url)

    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black
    val switchColors = if (isAmoled) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = Color.Black,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
        )
    } else {
        SwitchDefaults.colors()
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (server != null) stringResource(R.string.home_edit) else stringResource(R.string.server_add),
                    style = MaterialTheme.typography.headlineSmall
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.server_name)) },
                    placeholder = { Text(stringResource(R.string.server_name_hint)) },
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

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.server_auto_connect),
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = stringResource(R.string.server_auto_connect_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = { autoConnect = it },
                            colors = switchColors
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.server_cancel))
                    }
                    TextButton(
                        onClick = {
                            val normalizedUrl = validateAndNormalizeUrl(url)
                            urlError = when {
                                url.isBlank() -> urlRequiredText
                                normalizedUrl == null -> urlInvalidText
                                else -> null
                            }

                            if (urlError == null && normalizedUrl != null) {
                                val finalName = name.trim().ifBlank {
                                    deriveServerNameFromUrl(normalizedUrl)
                                }
                                onSave(
                                    finalName,
                                    normalizedUrl,
                                    username.ifBlank { "opencode" },
                                    password,
                                    autoConnect
                                )
                            }
                        }
                    ) {
                        Text(stringResource(R.string.server_save))
                    }
                }
            }
        }
    }
}
