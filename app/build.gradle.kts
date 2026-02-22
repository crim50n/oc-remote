import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    kotlin("kapt")
}

android {
    namespace = "dev.minios.ocremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.minios.ocremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val hasPropertiesFile = File("app/keystore/signing.properties").exists()
    if (hasPropertiesFile) {
        val props = Properties()
        props.load(FileInputStream(file("keystore/signing.properties")))
        val alias = props["keystore.alias"] as String
        signingConfigs {
            create("release") {
                storeFile = file(props["keystore"] as String)
                storePassword = props["keystore.password"] as String
                keyAlias = alias
                keyPassword = props["keystore.password"] as String
            }
        }
        println("[Signature] -> Build will be signed with: $alias")
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Android Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.51")
    kapt("com.google.dagger:hilt-android-compiler:2.51")

    // Ktor Client (OkHttp engine for proper SSE streaming support)
    val ktorVersion = "2.3.11"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-client-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
    implementation("io.ktor:ktor-client-auth:$ktorVersion")

    // Kotlinx Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Markdown Rendering (mikepenz/multiplatform-markdown-renderer)
    val markdownRendererVersion = "0.28.0"
    implementation("com.mikepenz:multiplatform-markdown-renderer:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil2:$markdownRendererVersion")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:$markdownRendererVersion")

    // WebView fallback (kept for legacy)
    implementation("androidx.webkit:webkit:1.11.0")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Accompanist (for SwipeRefresh)
    implementation("com.google.accompanist:accompanist-swiperefresh:0.34.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

kapt {
    correctErrorTypes = true
}
