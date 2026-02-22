package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Server Configuration - stored server connection details
 */
@Serializable
data class ServerConfig(
    val id: String, // UUID
    val url: String, // e.g. http://192.168.1.100:4096
    val username: String = "opencode",
    val password: String? = null,
    val name: String? = null, // User-friendly name
    val autoConnect: Boolean = false,
    val lastConnected: Long? = null,
    val isHealthy: Boolean = false
) {
    val displayName: String
        get() = name ?: url
    
    val host: String
        get() = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url.substringAfter("://").substringBefore(":")
        }
    
    val port: Int
        get() = try {
            val parsed = java.net.URL(url)
            val explicitPort = parsed.port
            if (explicitPort != -1) {
                explicitPort
            } else {
                parsed.defaultPort // 80 for http, 443 for https
            }
        } catch (e: Exception) {
            url.substringAfterLast(":").toIntOrNull() ?: 80
        }
}

/**
 * Server Health - result of health check
 */
@Serializable
data class ServerHealth(
    val healthy: Boolean,
    val version: String? = null
)
