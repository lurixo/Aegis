plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.aegis.tools.DictBuilderKt")
    applicationDefaultJvmArgs = listOf("-Xmx1536m")
}
