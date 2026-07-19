import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.firebase.perf)
}

// ─── Signing from environment variables or Gradle properties ─────────────────
fun env(vararg names: String): String =
    names.firstNotNullOfOrNull {
        System.getenv(it) ?: (project.findProperty(it) as? String)
    }.orEmpty()

val keystoreBase64   = env("KEYSTORE_BASE64")
val keystorePathEnv  = env("KEYSTORE_PATH", "KEYSTORE_FILE")
val keystorePassword = env("KEYSTORE_PASSWORD", "KEY_STORE_PASSWORD")
val releaseKeyAlias    = env("KEYSTORE_ALIAS", "KEY_ALIAS", "KEY_ALIAS_NAME")
val releaseKeyPassword = env("KEY_PASSWORD", "KEY_ALIAS_PASSWORD")

val resolvedKeystoreFile: File? by lazy {
    when {
        keystoreBase64.isNotBlank() -> {
            val decoded = Base64.getDecoder().decode(keystoreBase64)
            val tmp = layout.buildDirectory.get().asFile
                .resolve("signing/release.keystore")
                .also { it.parentFile?.mkdirs(); it.writeBytes(decoded) }
            tmp
        }
        keystorePathEnv.isNotBlank() -> File(keystorePathEnv)
        else -> null
    }
}

val canSign = resolvedKeystoreFile != null && keystorePassword.isNotBlank() &&
              releaseKeyAlias.isNotBlank() && releaseKeyPassword.isNotBlank()

android {
    namespace  = "com.exapps.mangaworld"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.exapps.mangaworld"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 120
        versionName   = "6.0.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile     = resolvedKeystoreFile
                storePassword = keystorePassword
                keyAlias      = releaseKeyAlias
                keyPassword   = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
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

    // Replaces deprecated kotlinOptions {} — required by Gradle 9 / AGP 8+
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
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
    implementation(libs.androidx.documentfile)
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    // Widgets / Glance
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Extras
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.palette.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.config.ktx)
    implementation(libs.firebase.messaging.ktx)
    implementation("com.google.firebase:firebase-database")
    implementation(libs.firebase.crashlytics.ktx)
    implementation(libs.firebase.analytics.ktx)
    implementation(libs.firebase.perf.ktx)
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")
    implementation(libs.play.services.auth)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Facebook SDK
    implementation("com.facebook.android:facebook-android-sdk:16.3.0")
    implementation("com.facebook.android:facebook-login:16.3.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.room.testing)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.test.manifest)
}
