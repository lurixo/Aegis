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
        val releaseName = "0.1.0-debug.56"
        val debugSeq = releaseName.substringAfterLast("-debug.").toIntOrNull()
            ?: error("versionName '$releaseName' must end in '-debug.<N>' so versionCode can derive from it")
        require(debugSeq >= 2) {
            "derived versionCode ($debugSeq) must exceed the published versionCode 1 so installs count as updates"
        }
        versionCode = debugSeq
        versionName = releaseName
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
