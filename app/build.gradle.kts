plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.catsmoker.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.catsmoker.app"
        minSdk = 27
        targetSdk = 36
        versionCode = 6
        versionName = "1.8.0"

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