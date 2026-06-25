plugins {
    // version-less: the Kotlin Gradle plugin is already on the build classpath (via the root
    // project's kotlin-android declaration); kotlin.jvm shares that artifact.
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.aegis.tools.DictBuilderKt")
    // Host dict build streams + external-sorts, so a modest heap is plenty.
    applicationDefaultJvmArgs = listOf("-Xmx1536m")
}
