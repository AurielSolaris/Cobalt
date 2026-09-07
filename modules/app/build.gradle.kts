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
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
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

dependencies {
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
