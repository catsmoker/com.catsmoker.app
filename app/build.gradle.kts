plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.catsmoker.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.catsmoker.app"
        minSdk = 27
        targetSdk = 36
        versionCode = 6
        versionName = "1.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
        aidl = true // Required for Shizuku UserService
    }

    // Prevents build failure if minor warnings occur
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    ndkVersion = "27.0.12077973"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // --- AndroidX Core & UI ---
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.activity)
    implementation(libs.fragment)
    implementation(libs.core)
    implementation(libs.annotation)
    implementation(libs.documentfile)
    implementation(libs.core.splashscreen)

    // --- Jetpack Compose ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.google.fonts)

    // --- Ads ---
    implementation(libs.startio.sdk)

    // --- Root & System ---
    implementation(libs.libsu.core)
    implementation(libs.core.ktx) // For Root operations
    compileOnly(libs.api)          // Xposed API

    // --- Shizuku ---
    implementation(libs.shizuku.api)
    implementation(libs.provider)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
