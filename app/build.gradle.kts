plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val configuredProxyBaseUrl = providers.gradleProperty("AI_PROXY_BASE_URL")
    .orElse("http://10.0.2.2:8787/")
    .get()
val configuredAppToken = providers.gradleProperty("APP_API_TOKEN").orElse("").get()

android {
    namespace = "com.sightsync.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sightsync.assistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "AI_PROXY_BASE_URL",
            buildConfigString(configuredProxyBaseUrl),
        )
        buildConfigField(
            "String",
            "APP_API_TOKEN",
            buildConfigString(configuredAppToken),
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val validateReleaseProxyConfig = tasks.register("validateReleaseProxyConfig") {
    doLast {
        val releaseBaseUrl = providers.gradleProperty("AI_PROXY_BASE_URL").orNull?.trim().orEmpty()
        val releaseToken = providers.gradleProperty("APP_API_TOKEN").orNull?.trim().orEmpty()
        if (!releaseBaseUrl.startsWith("https://")) {
            throw GradleException("AI_PROXY_BASE_URL must start with https:// for release")
        }
        if (releaseToken.isBlank()) {
            throw GradleException("APP_API_TOKEN is required for release")
        }
        if (releaseToken.lowercase() in setOf("dev-token", "change-me", "changeme")) {
            throw GradleException("APP_API_TOKEN must not use a placeholder for release")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseProxyConfig)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
