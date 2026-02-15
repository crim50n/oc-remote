// Top-level build file
plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.23" apply false
    id("com.google.dagger.hilt.android") version "2.51" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.23" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
