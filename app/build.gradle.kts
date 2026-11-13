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
        versionCode = 9
        versionName = "0.2.7"
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
