// Modified for JiaJia Cast.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mirrorcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mirrorcast.lan"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "0.2.8"
    }

    flavorDimensions += "device"
    productFlavors {
        create("standard") {
            dimension = "device"
            versionCode = 14
            versionName = "0.2.12"
            buildConfigField("boolean", "RECEIVER_ONLY", "false")
        }
        create("legacyReceiver") {
            dimension = "device"
            minSdk = 23
            applicationIdSuffix = ".receiver"
            buildConfigField("boolean", "RECEIVER_ONLY", "true")
        }
    }

    buildFeatures {
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
dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core:1.12.0")
}
