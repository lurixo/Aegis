import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import javax.inject.Inject

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
        versionName = "0.1.0-beta.43"
        versionCode = 125
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

    buildFeatures {
        compose = true
    }

    androidResources {
        ignoreAssetsPatterns += listOf(
            "aegis_dict.bin",
            "aegis_t9.bin",
            "aegis_jianpin.bin",
            "aegis_lm.bin",
            "aegis_english.bin",
            "aegis_en_full.bin",
            "wanxiang-lts-zh-hans.gram",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

abstract class GenerateTghGrading : DefaultTask() {
    @get:InputFile
    abstract val authority: RegularFileProperty

    @get:InputFile
    abstract val generator: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun generate() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val target = root.resolve("com/aegis/ime/dict/aegis_tgh.bin")
        check(target.parentFile.mkdirs()) { "could not create ${target.parentFile}" }
        execOps.exec {
            commandLine(
                "python3",
                generator.get().asFile.absolutePath,
                "--authority",
                authority.get().asFile.absolutePath,
                "--out",
                target.absolutePath,
            )
        }
        verify(target)
    }

    private fun verify(target: File) {
        check(target.isFile) { "the grading generator did not write ${target.absolutePath}" }
        val bytes = target.readBytes()
        check(bytes.size > HEADER_LEN) { "the grading resource is ${bytes.size} bytes" }
        check(
            bytes[0] == 'A'.code.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'G'.code.toByte() && bytes[3] == 'T'.code.toByte(),
        ) { "the grading resource carries a bad magic" }
        val header = ByteBuffer.wrap(bytes, 0, HEADER_LEN).order(ByteOrder.LITTLE_ENDIAN)
        check(header.getInt(4) == VERSION) { "the grading resource is version ${header.getInt(4)}" }
        val count = header.getInt(8)
        check(count == ENTRY_COUNT) { "the grading resource holds $count entries, expected $ENTRY_COUNT" }
        var pos = HEADER_LEN
        var previous = 0
        for (i in 0 until count) {
            var shift = 0
            var delta = 0
            while (true) {
                check(pos < bytes.size) { "the grading resource runs out of bytes at entry $i" }
                check(shift <= MAX_VARINT_SHIFT) { "the grading resource has an over-wide delta at entry $i" }
                val b = bytes[pos++].toInt()
                delta = delta or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            check(delta > 0) { "the grading resource is not ascending at entry $i" }
            previous += delta
            check(Character.isValidCodePoint(previous)) { "the grading resource has a bad code point at entry $i" }
        }
        val packedLen = (count + 3) / 4
        check(pos + packedLen == bytes.size) {
            "the grading resource is ${bytes.size} bytes, expected ${pos + packedLen}"
        }
        val counts = IntArray(3)
        for (i in 0 until count) {
            val level = ((bytes[pos + (i shr 2)].toInt() ushr (2 * (i and 3))) and 0x3) + 1
            check(level in 1..3) { "the grading resource holds level $level at entry $i" }
            counts[level - 1]++
        }
        check(counts[0] == LEVEL1_COUNT && counts[1] == LEVEL2_COUNT && counts[2] == LEVEL3_COUNT) {
            "the grading resource counts ${counts[0]}/${counts[1]}/${counts[2]}, " +
                "expected $LEVEL1_COUNT/$LEVEL2_COUNT/$LEVEL3_COUNT"
        }
        logger.lifecycle("grading resource verified ${bytes.size} bytes ${counts[0]}/${counts[1]}/${counts[2]}")
    }

    private companion object {
        const val VERSION = 1
        const val HEADER_LEN = 12
        const val MAX_VARINT_SHIFT = 14
        const val ENTRY_COUNT = 8105
        const val LEVEL1_COUNT = 3500
        const val LEVEL2_COUNT = 3000
        const val LEVEL3_COUNT = 1605
    }
}

val generateTghGrading = tasks.register<GenerateTghGrading>("generateTghGrading") {
    authority.set(layout.projectDirectory.file("src/main/assets-src/tongyong-guifan-hanzi-8105.tsv"))
    generator.set(layout.projectDirectory.file("../tools/release/build_tgh_asset.py"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(
            generateTghGrading,
            GenerateTghGrading::outputDir,
        )
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
    inputs.files(
        layout.projectDirectory.file("src/main/assets/aegis_dict.bin"),
        layout.projectDirectory.file("src/main/assets/aegis_t9.bin"),
        layout.projectDirectory.file("src/main/assets/aegis_jianpin.bin"),
        layout.projectDirectory.file("src/main/assets/aegis_lm.bin"),
    ).withPropertyName("runtimeDictionaryAssets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        layout.projectDirectory.file("../THIRD_PARTY_LICENSES.md"),
        layout.projectDirectory.file("../README.md"),
        layout.projectDirectory.file("../README.zh-CN.md"),
        layout.projectDirectory.file("../aegis-build-info.json"),
        layout.projectDirectory.file("../tools/release/build_dictionary_pack.py"),
        layout.projectDirectory.file("../tools/t2s-data/adjudications.tsv"),
        layout.projectDirectory.file("src/main/AndroidManifest.xml"),
    ).withPropertyName("repositoryFilesReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("../tools/t2s-data"))
        .withPropertyName("t2sDataReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(layout.projectDirectory.dir("src/main/res"))
        .withPropertyName("sourceResourcesReadByTests")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("auditSweepGate", providers.environmentVariable("AEGIS_AUDIT_FULL").orElse(""))
    inputs.property("heavyAuditSweepGate", providers.environmentVariable("AEGIS_AUDIT_HEAVY").orElse(""))
    inputs.property("boostReportGate", providers.environmentVariable("AEGIS_BOOST_REPORT").orElse(""))
    inputs.property("boostSmokeGate", providers.environmentVariable("AEGIS_BOOST_SMOKE").orElse(""))
    inputs.property("boostThreads", providers.environmentVariable("AEGIS_BOOST_THREADS").orElse(""))
    inputs.property(
        "coverageIdentityDigestDump",
        providers.environmentVariable("AEGIS_COVERAGE_DIGEST_DUMP").orElse(""),
    )
    inputs.file(
        providers.environmentVariable("AEGIS_COVERAGE_DIGEST_BASELINE")
            .map(::file)
            .orElse(layout.projectDirectory.file("src/test/resources/coverage-identity-7907381b.tsv").asFile),
    ).withPropertyName("coverageIdentityDigestBaseline")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(providers.environmentVariable("AEGIS_GRAM").map(::file))
        .optional()
        .withPropertyName("externalGrammarModel")
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.file(providers.environmentVariable("AEGIS_ENGLISH").map(::file))
        .optional()
        .withPropertyName("externalEnglishTable")
        .withPathSensitivity(PathSensitivity.NONE)
}

tasks.register("verifyExternalModelsNotPackaged") {
    val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val releaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
    dependsOn("assembleDebug", "assembleRelease")
    inputs.files(debugApk, releaseApk)
    doLast {
        val exactForbidden = setOf(
            "aegis_dict.bin",
            "aegis_dict_full.bin",
            "aegis_t9.bin",
            "aegis_t9_full.bin",
            "aegis_jianpin.bin",
            "aegis_jianpin_full.bin",
            "aegis_lm.bin",
            "aegis_english.bin",
            "aegis_en_full.bin",
        )
        for (apk in listOf(debugApk.get().asFile, releaseApk.get().asFile)) {
            check(apk.isFile) { "missing APK ${apk.absolutePath}" }
            val leaked = ArrayList<String>()
            ZipFile(apk).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.startsWith("assets/")) continue
                    val basename = name.substringAfterLast('/')
                    if (
                        basename in exactForbidden ||
                        basename.endsWith(".gram") ||
                        basename.startsWith("aegis_en")
                    ) {
                        leaked += name
                    }
                }
            }
            check(leaked.isEmpty()) { "external test models leaked into ${apk.name}: $leaked" }
            logger.lifecycle("verified ${apk.name}: no external dictionary, English, or grammar model assets")
        }
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
