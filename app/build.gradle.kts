import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aegis.ime"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aegis.ime"
        minSdk = 34
        targetSdk = 37
        versionName = "0.1.0"
        versionCode = 1
    }

    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            applicationIdSuffix = ".debug"
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
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
    maxHeapSize = "1g"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val scratchDir = layout.buildDirectory.dir("tmp/test-jvm/$name").get().asFile
    systemProperty("java.io.tmpdir", scratchDir.absolutePath)
    doFirst {
        check(scratchDir.deleteRecursively()) { "could not clear $scratchDir" }
        check(scratchDir.mkdirs()) { "could not create $scratchDir" }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(project(":tools"))
}
