package dev.minios.ocremote.core.network

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS configuration that accepts any certificate and any hostname.
 *
 * Used only for servers that explicitly opt in via the
 * "Allow self-signed certificates" toggle in the server dialog.
 * Never use this for servers you do not fully trust.
 */
object InsecureTls {

    /**
     * Trust-all [X509TrustManager]. Accepts every certificate chain.
     */
    val trustAllManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * An [SSLSocketFactory] that trusts every certificate.
     */
    fun createSslSocketFactory(): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * Apply trust-all configuration to an [okhttp3.OkHttpClient.Builder].
     */
    fun applyToOkHttp(builder: okhttp3.OkHttpClient.Builder) {
        builder.sslSocketFactory(createSslSocketFactory(), trustAllManager)
        builder.hostnameVerifier { _, _ -> true }
    }
}
