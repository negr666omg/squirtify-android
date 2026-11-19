import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val keystoreProps = Properties().apply {
    val f = rootProject.file("squidify.keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "org.squidify.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.squidify.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.7.5"
    }

    val keystoreFile = rootProject.file("squidify.keystore")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: System.getenv("SQUIDIFY_STORE_PASSWORD")
                    ?: error("Set storePassword in squidify.keystore.properties or SQUIDIFY_STORE_PASSWORD")
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: error("Set keyAlias in squidify.keystore.properties")
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: System.getenv("SQUIDIFY_KEY_PASSWORD")
                    ?: error("Set keyPassword in squidify.keystore.properties or SQUIDIFY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Prefer the production keystore when present. CI has no private key,
            // so fall back to the standard debug key to keep the Release APK installable.
            signingConfig = if (keystoreFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
        // Release-mode installable build for on-device performance testing.
        // Uses the debug signing key because no production keystore is stored in the repo.
        // A separate package keeps the user's normal installation untouched.
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/LICENSE.md"
        resources.excludes += "/META-INF/LICENSE-notice.md"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-text-google-fonts") // optional font loading helpers

    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")

    implementation("io.coil-kt:coil-compose:2.7.0")

    // SAF DocumentFile helpers for the user-visible Squidify folder
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}
