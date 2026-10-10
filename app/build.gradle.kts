import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.weighttrend"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.weighttrend"
        minSdk = 31
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
    }

    // A fixed key, so every build from GitHub installs as an update over the
    // previous one (a new random debug key would force an uninstall = data loss).
    // The key itself is not in the repository: the CI workflow restores it
    // from the WEIGHTTREND_KEYSTORE secret before building.
    val personalKey = file("keystore/personal.jks")
    signingConfigs {
        create("personal") {
            storeFile = personalKey
            storePassword = "weighttrend"
            keyAlias = "weighttrend"
            keyPassword = "weighttrend"
        }
    }

    buildTypes {
        getByName("debug") {
            if (personalKey.exists()) signingConfig = signingConfigs.getByName("personal")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (personalKey.exists()) signingConfigs.getByName("personal") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
