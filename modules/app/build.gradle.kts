plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "app.auriel.cobalt"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.auriel.cobalt"
        // 29, not 24, because that is Chromium's floor: chrome_public_apk is
        // built with minSdkVersion 29 and the embedded Java assumes it. Keeping
        // 24 here would let the app install on devices the engine cannot run
        // on, which is a crash rather than a degraded experience.
        //
        // org.chromium.build.BuildConfig.MIN_SDK_VERSION must match this.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    // The Chromium AAR carries one ABI, because that is what the Chromium
    // checkout is configured to build (target_cpu = "arm64" in args.gn). Saying
    // so explicitly keeps the APK honest rather than letting it claim ABIs it
    // has no engine for.
    defaultConfig {
        ndk { abiFilters += "arm64-v8a" }
    }

    // Chromium's runtime assets live in their own source set, written by
    // tools/build/export-aar.sh. Keeping them out of src/main/assets means the
    // export can clear its own destination without touching files this
    // repository owns, which is a mistake this made once already.
    sourceSets {
        getByName("main") {
            assets.srcDir("src/chromium/assets")
        }
    }

    packaging {
        resources {
            // The AAR's classes.jar still carries a few root-level metadata
            // files from libraries the app also has, and two copies of a
            // packaged resource is a hard failure:
            //
            //   2 files found with path 'DebugProbesKt.bin'
            //
            // None of these is used at runtime -- they are debugger and build
            // metadata -- so dropping the duplicate is the whole fix.
            excludes += setOf(
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
            )
        }

        jniLibs {
            // libchrome.so is 205 MB and must be mapped out of the APK rather
            // than extracted to disk at install time; see docs/apk-size.md,
            // where the same setting on the Chromium build is blocked by the
            // crashpad handler. Nothing here has that problem.
            useLegacyPackaging = false
        }
    }

    buildTypes {
        debug {
            // The nightly channel installs alongside stable rather than over it,
            // so a bug report can never be ambiguous about which build it came
            // from. See docs/branching.md.
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-nightly"
            resValue("string", "app_name", "Cobalt Nightly")
        }
        release {
            isMinifyEnabled = false
            resValue("string", "app_name", "Cobalt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// The Chromium engine, copied in by tools/build/export-aar.sh.
//
// Optional on purpose. The AAR is ~260 MB, gitignored, and reproducible from
// the Chromium checkout in a minute -- so a fresh clone will not have it, and a
// fresh clone must still build. Without it the app runs on the 0.1.0 document
// engine, which is exactly what DocumentEngine exists for.
val chromiumAar = file("libs/cobalt-content.aar")
val hasChromium = chromiumAar.exists()

if (hasChromium) {
    // com.google.guava:listenablefuture:1.0 is an empty stub artifact. It
    // exists only so Maven can resolve a version conflict, and it declares
    // ListenableFuture without implementing anything. androidx pulls it in
    // transitively, and the AAR carries the real Guava, so the two collide:
    //
    //   Duplicate class com.google.common.util.concurrent.ListenableFuture
    //
    // Dropping the stub is the standard resolution and the right one here --
    // the real class is present, from Chromium.
    configurations.all {
        exclude(group = "com.google.guava", module = "listenablefuture")
    }
}

dependencies {
    if (hasChromium) {
        implementation(files(chromiumAar))
    }

    implementation(project(":modules:core"))
    implementation(project(":modules:engine"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
