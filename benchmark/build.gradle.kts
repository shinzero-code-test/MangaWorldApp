import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

/**
 * Device measure gate for the Stage-2 perf work (U-26 / U-27).
 *
 * Spec: tmp/review/stage2/perf.md §"Measure spec" (MB-1 … MB-5).
 * Rationale + how to run: benchmark/README.md.
 *
 * This is a `com.android.test` module: it produces a *test* APK that is
 * installed next to the app under test and drives it from the outside
 * (UiAutomator + shell). It deliberately does NOT depend on the app module:
 * a macrobenchmark measures the installed APK, and a compile-time coupling
 * would put app classes inside the harness.
 */
android {
    namespace = "com.exapps.mangaworld.benchmark"
    compileSdk = 36

    // The app under test. Its `benchmark` build type is release-like,
    // non-debuggable and debug-signed (see app/build.gradle.kts).
    //
    // `targetProjectPath` lives on the `com.android.test` EXTENSION
    // (`TestExtension.targetProjectPath`), NOT on `defaultConfig` — the
    // Groovy snippets in the AGP docs put it inside `defaultConfig`, but
    // Groovy resolves it through the closure owner while the Kotlin DSL has
    // no such delegation, so `defaultConfig { targetProjectPath = … }` is an
    // "unresolved reference" here (and it was one of the two reasons this
    // module never compiled).
    targetProjectPath = ":app"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // MacrobenchmarkRule refuses a harness that instruments the app under
    // test ("NOT-SELF-INSTRUMENTING" configuration error): the benchmark
    // code would be loaded INTO the app's process, which makes killing,
    // compiling and launching the app impossible. This property makes AGP
    // generate a test APK whose instrumentation targets its own package.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // Paired with the app's `benchmark` build type (same build-type
        // name -> AGP pairs the variants). The harness APK itself may stay
        // debuggable: it is only a driver, and a debuggable APK lets
        // `adb shell run-as` pull the metric JSON off the device after a run.
        //
        // NOTE: a `com.android.test` module has ONLY the `debug` build type
        // (AGP creates it) — `getByName("release")` does not exist here and
        // aborts EVERY Gradle task in the repo (build scripts are compiled
        // before any task is configured). Same for `signingConfig`: inside
        // `buildTypes { }` the receiver is a TestBuildType, so the signing
        // config must be resolved through `signingConfigs`.
        create("benchmark") {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true
        }
        // The default `debug` variant pairs with the app's `debug` variant.
        // It is used by the exact-counter instrumentation tests
        // (src/debug/...), which need the app's DEBUG build because the
        // OkHttp request log is only enabled in debug builds.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    testOptions {
        // Frame timings are meaningless while the system animates.
        animationsDisabled = true
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.ext)
    implementation(libs.androidx.test.core)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    // Metrics + CompilationMode (benchmark-macro) and the JUnit4 rule
    // (benchmark-macro-junit4). The rule lives in its own artifact.
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.benchmark.macro.junit4)
}
