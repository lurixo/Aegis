import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.aegis.ime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aegis.ime"
        minSdk = 34
        targetSdk = 37
        // versionName is the SINGLE human-edited source of the release identity; versionCode is DERIVED from
        // its trailing debug sequence number so the two can never drift. The old bug: the name was bumped every
        // release (…-debug.50) while versionCode stayed 1, which Android rejects as a non-update — so users
        // could never update across versions. Now a release edits ONLY the name and the code auto-increments
        // with it (debug.N -> versionCode N), monotonically. Every published build so far shipped versionCode 1,
        // so any derived code >= 2 is a strictly higher, valid update that never reuses a published number.
        val releaseName = "0.1.0-debug.51"
        val debugSeq = releaseName.substringAfterLast("-debug.").toIntOrNull()
            ?: error("versionName '$releaseName' must end in '-debug.<N>' so versionCode can derive from it")
        require(debugSeq >= 2) {
            "derived versionCode ($debugSeq) must exceed the published versionCode 1 so installs count as updates"
        }
        versionCode = debugSeq
        versionName = releaseName
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
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Compose UI tests run on the JVM via Robolectric (navigation click-through, user-dict search/list).
    testImplementation(libs.androidx.ui.test.junit4)
    testImplementation(libs.androidx.ui.test.manifest)

    constraints {
        implementation(libs.androidx.lifecycle.runtime.compose) {
            because("Keep lifecycle-runtime-compose aligned with the API 37 stable toolchain")
        }
        implementation(libs.androidx.core) {
            because("Keep core aligned with core-ktx for the API 37 stable toolchain")
        }
    }
}
