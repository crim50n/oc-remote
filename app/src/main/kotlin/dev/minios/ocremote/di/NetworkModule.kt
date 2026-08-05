package dev.minios.ocremote.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.minios.ocremote.core.network.InsecureTls
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opencode_prefs")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    @Named("secure")
    fun provideHttpClient(json: Json): HttpClient = buildHttpClient(json)

    @Provides
    @Singleton
    @Named("insecure")
    fun provideInsecureHttpClient(json: Json): HttpClient = buildHttpClient(json, trustAll = true)

    private fun buildHttpClient(json: Json, trustAll: Boolean = false): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        
        install(Logging) {
            logger = Logger.ANDROID
            level = LogLevel.NONE
        }
        
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }

        install(HttpRedirect) {
            // Reverse proxies commonly redirect the API origin; 307/308 preserve the prompt POST body.
            checkHttpMethod = false
        }

        install(WebSockets)
        
        install(Auth) {
            // Auth will be configured per-request based on server config
        }
        
        engine {
            config {
                // OkHttp-specific: disable response body buffering for streaming
                retryOnConnectionFailure(true)
                if (trustAll) {
                    // Accept any certificate + hostname (opt-in per server).
                    InsecureTls.applyToOkHttp(this)
                }
            }
        }
        
        // Default headers will be set per-request in OpenCodeApi
    }
    
    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }
}
