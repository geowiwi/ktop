import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // version set in root build.gradle.kts
}

// Local, uncommitted settings (local.properties) or environment (CI):
// VERSION_CODE / VERSION_NAME and RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD /
// RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD for release signing.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localOrEnv(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.w1w1.ktop"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.w1w1.ktop"
        minSdk = 23
        targetSdk = 34
        // Must stay above 53 (last pre-0.1 dev build) so existing installs can update.
        versionCode = localOrEnv("VERSION_CODE")?.toIntOrNull() ?: 100
        versionName = localOrEnv("VERSION_NAME") ?: "0.1.0"
    }

    signingConfigs {
        val storeFilePath = localOrEnv("RELEASE_STORE_FILE")
        val keystore = storeFilePath?.let { p ->
            listOf(rootProject.file(p), file(p)).firstOrNull { it.isFile }
        }
        if (storeFilePath != null && keystore == null) {
            logger.warn("ktop: RELEASE_STORE_FILE '$storeFilePath' not found - release build stays unsigned")
        }
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = localOrEnv("RELEASE_STORE_PASSWORD")
                keyAlias = localOrEnv("RELEASE_KEY_ALIAS")
                keyPassword = localOrEnv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Signed only if a keystore is configured (local.properties / CI secrets).
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("io.hammerhead:karoo-ext:1.1.8")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.compose.ui:ui:1.10.6")
    implementation("androidx.compose.material:material:1.10.6")
    implementation("androidx.compose.ui:ui-tooling-preview:1.10.6")
    implementation("androidx.glance:glance:1.1.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // App screens in Ki2 style (same libraries Ki2 uses)
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
