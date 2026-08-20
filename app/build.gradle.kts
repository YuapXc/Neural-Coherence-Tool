import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}
val syncSigningSalt = localProperties.getProperty("SYNC_SIGNING_SALT")
    ?: System.getenv("SYNC_SIGNING_SALT")
    ?: ""
val signingProperties = Properties().apply {
    val file = rootProject.file("signing.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}
val hasReleaseSigning = signingProperties.getProperty("storeFile") != null

android {
    namespace = "io.github.neuralcoherence.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.neuralcoherence.probe"
        minSdk = 29
        targetSdk = 35
        versionCode = 133
        versionName = "1.3.3"
        buildConfigField("String", "SYNC_SIGNING_SALT", "\"${syncSigningSalt.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
