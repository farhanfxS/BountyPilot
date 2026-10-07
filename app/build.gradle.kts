plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.bountypilot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bountypilot.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "4.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
