plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ─── Signing from environment variables ──────────────────────────────────────
// Supports both a path-based keystore and a base64-encoded one (GitHub Actions).
//
//  Required env vars (any of these naming conventions work):
//    KEYSTORE_PATH  or  KEYSTORE_FILE          – absolute path to .jks / .keystore
//    KEYSTORE_BASE64                            – base64-encoded keystore (alternative)
//    KEYSTORE_PASSWORD  or  KEY_STORE_PASSWORD  – store password
//    KEY_ALIAS      or  KEY_ALIAS_NAME          – key alias
//    KEY_PASSWORD   or  KEY_ALIAS_PASSWORD      – key password
// ─────────────────────────────────────────────────────────────────────────────
fun env(vararg names: String): String =
    names.firstNotNullOfOrNull { System.getenv(it) }.orEmpty()

val keystoreBase64   = env("KEYSTORE_BASE64")
val keystorePathEnv  = env("KEYSTORE_PATH", "KEYSTORE_FILE")
val keystorePassword = env("KEYSTORE_PASSWORD", "KEY_STORE_PASSWORD")
val keyAlias         = env("KEY_ALIAS", "KEY_ALIAS_NAME")
val keyPassword      = env("KEY_PASSWORD", "KEY_ALIAS_PASSWORD")

// Decode base64 keystore to a temp file if no direct path is given
val resolvedKeystoreFile: File? by lazy {
    when {
        keystoreBase64.isNotBlank() -> {
            val decoded = java.util.Base64.getDecoder().decode(keystoreBase64)
            val tmp = File(rootProject.buildDir, "signing/release.keystore").also {
                it.parentFile?.mkdirs()
                it.writeBytes(decoded)
            }
            tmp
        }
        keystorePathEnv.isNotBlank() -> File(keystorePathEnv)
        else -> null
    }
}

val canSign = resolvedKeystoreFile != null && keystorePassword.isNotBlank() &&
              keyAlias.isNotBlank() && keyPassword.isNotBlank()

android {
    namespace  = "com.exapps.mangaworld"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.exapps.mangaworld"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 1
        versionName   = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile     = resolvedKeystoreFile
                storePassword = keystorePassword
                keyAlias      = keyAlias
                keyPassword   = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix  = ".debug"
            versionNameSuffix    = "-debug"
            isDebuggable         = true
        }
        release {
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
    buildFeatures {
        compose     = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.datastore)
    implementation(libs.coroutines.android)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // Paging
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.play.services.base)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    // Accompanist
    implementation(libs.accompanist.systemuicontroller)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.test.manifest)
}
