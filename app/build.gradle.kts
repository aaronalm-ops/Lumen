plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Values that CI injects through the environment. Every one of them has a safe default so a
// local build, or a CI build with no secrets configured, still succeeds.
val githubOwner: String = (project.findProperty("githubOwner") as String?)?.takeIf { it.isNotBlank() } ?: "aaronalm-ops"
val envVersionName: String = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val envVersionCode: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

// Named env* so they cannot be confused with the SigningConfig properties of the same name.
val envKeystorePath: String? = System.getenv("KEYSTORE_PATH")
val envKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val envKeyAlias: String? = System.getenv("KEY_ALIAS")
val envKeyPassword: String? = System.getenv("KEY_PASSWORD")
val hasReleaseKey: Boolean =
    !envKeystorePath.isNullOrBlank() &&
        !envKeystorePassword.isNullOrBlank() &&
        !envKeyAlias.isNullOrBlank() &&
        !envKeyPassword.isNullOrBlank() &&
        file(envKeystorePath.orEmpty()).exists()

android {
    namespace = "app.lumen.player"
    // Compose 1.12 and later require compileSdk 37 (developer.android.com/develop/ui/compose/compiler);
    // lifecycle 2.11.0 is also compiled against API 37. targetSdk stays 36.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aaronalmeida.lumen"
        minSdk = 29
        targetSdk = 36
        versionCode = envVersionCode
        versionName = envVersionName
        buildConfigField("String", "GITHUB_OWNER", "\"$githubOwner\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(envKeystorePath!!)
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Phase 0 stays unminified so stack traces in diagnostics are readable.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The spike must never fail to build because of a lint finding.
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // viewModelScope for MainViewModel (an empty compatibility artifact on new Lifecycle, harmless).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    // PlayerView for inline video on Now Playing.
    implementation(libs.media3.ui)
    // Phase 1b: Drive streaming through a cached HTTP DataSource.
    implementation(libs.media3.datasource)
    implementation(libs.media3.database)
    // 0.5.0: Chromecast. CastPlayer swaps between ExoPlayer and the Cast receiver inside the one
    // MediaSession; the Cast framework is named so its version is pinned to what media3-cast expects.
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)

    // Phase 1b: Google Drive authorisation (AuthorizationClient, drive.readonly and drive.file) and the
    // Android 12 splash screen API with its compat shim.
    implementation(libs.play.services.auth)
    implementation(libs.core.splashscreen)

    // Phase 1a: home screen widget, album art tint, folder trees.
    implementation(libs.glance.appwidget)
    implementation(libs.palette.ktx)
    implementation(libs.documentfile)

    // 0.4.0: foldables and large screens. WindowInfoTracker (tabletop hinge), WindowMetricsCalculator
    // and WindowSizeClass (two panes from 600 dp).
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.core)

    // room-ktx was merged into room-runtime in 2.7.0 and is an empty artifact since then.
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}
