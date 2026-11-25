plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val configuredOrigins = providers.gradleProperty("pslink.allowedOrigins").orElse("").get()
val escapedOrigins = configuredOrigins.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.pslink.tv"
    compileSdk = 35

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.pslink.tv"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
        buildConfigField("String", "PSLINK_ALLOWED_ORIGINS", "\"$escapedOrigins\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:shared"))
    implementation(libs.appcompat)
    implementation(libs.core.ktx)
    implementation(libs.material)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
}
