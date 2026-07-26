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
        versionName = "0.1.0-beta.25"
        versionCode = 88
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

    androidResources {
        ignoreAssetsPatterns += listOf("aegis_dict.bin", "aegis_t9.bin", "aegis_jianpin.bin")
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

tasks.withType<Test>().configureEach {
    inputs.files(
        layout.projectDirectory.file("src/main/assets/aegis_dict.bin"),
        layout.projectDirectory.file("src/main/assets/aegis_t9.bin"),
        layout.projectDirectory.file("src/main/assets/aegis_jianpin.bin"),
    ).withPropertyName("runtimeDictionaryAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        layout.projectDirectory.file("../THIRD_PARTY_LICENSES.md"),
        layout.projectDirectory.file("../aegis-build-info.json"),
        layout.projectDirectory.file("../tools/release/build_dictionary_pack.py"),
        layout.projectDirectory.file("src/main/AndroidManifest.xml"),
    ).withPropertyName("repositoryFilesReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../tools/t2s-data"))
        .withPropertyName("t2sDataReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("sourceResourcesReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
    testImplementation(project(":tools"))

    constraints {
        implementation(libs.androidx.lifecycle.runtime.compose) {
            because("Keep lifecycle-runtime-compose aligned with the API 37 stable toolchain")
        }
        implementation(libs.androidx.core) {
            because("Keep core aligned with core-ktx for the API 37 stable toolchain")
        }
    }
}
