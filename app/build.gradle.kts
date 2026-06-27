import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.aegis.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aegis.ime"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Ship arm64-v8a only (drops the other ABIs of the one transitive native lib).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // A3/A2 interaction verification: Robolectric runs real View touch/scroll tests on the JVM
    // (dispatch MotionEvents, assert behaviour) so render/touch bugs are caught in CI, not on-device.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)

    // Clamp transitive AndroidX that the Compose BOM would otherwise raise to API-37-only
    // releases, until AGP 9.1 + compileSdk 37 are stable.
    constraints {
        implementation(libs.androidx.lifecycle.runtime.compose) {
            version { strictly("2.10.0") }
            because("lifecycle 2.11.0 requires compileSdk 37 / AGP 9.1")
        }
        implementation(libs.androidx.core) {
            version { strictly("1.18.0") }
            because("core 1.19.0 requires compileSdk 37 / AGP 9.1")
        }
    }
}
