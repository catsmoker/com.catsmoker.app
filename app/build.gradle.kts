import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt.android)
    // Firebase (google-services.json, project catsmoker-5b6c6, gitignored).
    // Shared by both variants — same project, automatic Analytics init.
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.catsmoker.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.catsmoker.app"
        minSdk = 27
        targetSdk = 37
        // versionCode 7: IFileService gained readFile/writeFile — the bump is what forces
        // Shizuku to restart the daemonized helper whose AIDL no longer matches (see the
        // ShellRunner KDoc). Shipped helpers keep serving the old AIDL until this moves.
        versionCode = 9
        versionName = "2.0.3"

        // Only locales the app actually ships (see res/xml/locales_config.xml): strips the
        // dozens of transitive locales dragged in by material/ads/splashscreen/work.
        // Shrinking alone cannot do this. Qualifiers match res/values-* dirs exactly;
        // in-app tags are en/ar/es/zh-CN (AppearanceStore), en-GB is a mirror.
        resConfigs("en", "en-rGB", "ar", "es", "zh-rCN")

        vectorDrawables {
            useSupportLibrary = true
        }

        // No per-variant IDs here: full declares STARTIO_APP_ID, playstore declares
        // ADMOB_* + the ADMOB_APP_ID manifest placeholder in their flavor blocks.
    }

    // Single codebase, two distribution variants:
    // full = complete GitHub app (spoof/LSPosed/Magisk/Start.io/self-updater),
    // playstore = Play-safe build (no spoof implementation, AdMob instead of Start.io).
    // src/main stays Play-safe shared code; variant code lives in src/full and src/playstore.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            // Full-only R8/ProGuard rules (Start.io, LSPosed, spoof models).
            proguardFile("src/full/proguard-rules.pro")
            // Full-only native source: the spoof half of the bridge (src/main/jni/catsmoker_spoof.c,
            // selected in Android.mk). Same library for both flavors, one extra translation unit —
            // the Play build must not carry spoof hooking, so this rides the flavor, not a flag.
            externalNativeBuild {
                ndkBuild {
                    arguments.add("CATSMOKER_FULL=1")
                }
            }
            val localProperties = Properties()
            val localPropertiesFile = project.rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localProperties.load(localPropertiesFile.inputStream())
            }
            val startIoId = localProperties.getProperty("STARTIO_APP_ID") ?: "205489527"
            buildConfigField("String", "STARTIO_APP_ID", "\"$startIoId\"")
        }
        create("playstore") {
            dimension = "distribution"
            val localProperties = Properties()
            val localPropertiesFile = project.rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localProperties.load(localPropertiesFile.inputStream())
            }
            // Real production IDs never enter git; unset keys fall back to Google's
            // documented sample (test) IDs, which serve test ads and are safe to build with.
            val admobAppId = localProperties.getProperty("ADMOB_APP_ID") ?: "ca-app-pub-3940256099942544~3347511713"
            val admobBannerId = localProperties.getProperty("ADMOB_BANNER_ID") ?: "ca-app-pub-3940256099942544/6300978111"
            val admobInterstitialId = localProperties.getProperty("ADMOB_INTERSTITIAL_ID") ?: "ca-app-pub-3940256099942544/1033173712"
            manifestPlaceholders["ADMOB_APP_ID"] = admobAppId
            buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
        }
    }

    // Release-signing credentials (git-ignored signing.properties at the repo root;
    // four keys required: storeFile, storePassword, keyAlias, keyPassword).
    // Shared by both variants; without the file, releases stay debug-signed so any
    // clone still builds.
    val signingProps = Properties()
    val signingPropsFile = project.rootProject.file("signing.properties")
    if (signingPropsFile.exists()) {
        signingProps.load(signingPropsFile.inputStream())
    }
    val hasReleaseSigning = signingProps.containsKey("storeFile")
        && signingProps.containsKey("storePassword")
        && signingProps.containsKey("keyAlias")
        && signingProps.containsKey("keyPassword")
    if (!hasReleaseSigning) {
        logger.warn("signing.properties missing or incomplete — release will be debug-signed.")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
        viewBinding = false
        compose = true
        buildConfig = true
        aidl = true // Required for Shizuku UserService
    }

    // Prevents build failure if minor warnings occur
    lint {
        abortOnError = false
        // Surfaces future unused-resource/deprecation warnings on release builds; with
        // abortOnError=false this can never block a release. Zero runtime/APK change.
        checkReleaseBuilds = true
    }

    ndkVersion = "28.2.13676358"

    // In-app native bridge (M152 NDK spike): ndk-build, whose scripts ship inside the NDK
    // itself, so no CMake SDK component is needed. The library is one C file today.
    externalNativeBuild {
        ndkBuild {
            path("src/main/jni/Android.mk")
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0"
            )
        }
    }
}

dependencies {
    // --- AndroidX Core & UI ---
    implementation(libs.material) // Theme.Material3.* parents in res/values/themes.xml
    implementation(libs.activity)
    implementation(libs.core)
    implementation(libs.documentfile)
    implementation(libs.core.splashscreen)
    implementation(libs.core.ktx)

    // --- Jetpack Compose ---
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.runtime.tracing)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.ktx)

    // --- Hilt DI ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    // @HiltWorker needs hilt-work's factory plus its own annotation processor.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- Background scheduling (the recurring dexopt sweep) ---
    implementation(libs.work.runtime.ktx)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)

    // --- JSON ---
    implementation(libs.gson)

    // --- Firebase Analytics (SHARED: full + playstore, same project) ---
    // No code references: initialization is automatic via the merged provider,
    // exactly as on the old playstore branch. No custom consent gating exists.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // --- Ads (variant-specific: Start.io on full, AdMob on playstore) ---
    // String-based configurations: flavor-qualified accessors (fullImplementation,
    // playstoreImplementation, ...) are not available as Kotlin-DSL members here.
    add("fullImplementation", libs.startio.sdk)
    add("playstoreImplementation", libs.play.services.ads)
    // Google Play Billing (remove_ads one-time product) is Play-only like AdMob.
    add("playstoreImplementation", libs.billing.ktx)

    // --- Root & System (shared: libsu + Shizuku serve the gaming engine on both) ---
    implementation(libs.libsu.core)
    // Xposed API is full-only and compileOnly (never packaged): only the LSPosed
    // module in src/full references it.
    add("fullCompileOnly", libs.api) // Xposed API

    // --- Shizuku ---
    implementation(libs.shizuku.api)
    implementation(libs.provider)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.json.test)
}

ksp {
    arg("dagger.fastInit", "ENABLED")
    arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
}