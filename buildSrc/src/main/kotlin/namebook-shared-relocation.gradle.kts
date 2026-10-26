plugins {
    id("com.gradleup.shadow")
}

// Every package the plugin shades is relocated here and nowhere else, so another plugin shipping
// a different version cannot clash. Add a package here when the plugin starts shading a new library.
// com.minecraftcitiesnetwork.namebook.api is deliberately NOT relocated: other plugins compile
// against those names and load them from this jar at runtime.
val prefix = "com.minecraftcitiesnetwork.namebook.libraries"

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    listOf(
        "com.minecraftcitiesnetwork.pluginInfrastructure",
        "org.mariadb",
        "org.mybatis",
        "org.apache.ibatis",
        "org.spongepowered",
        "org.yaml",
        "io.leangen.geantyref",
        "org.jetbrains.annotations",
        "org.intellij.lang",
        "org.jspecify",
        "net.kyori.option",
        "org.incendo.cloud",
        "com.fasterxml.jackson",
    ).forEach {
        relocate(it, "$prefix.$it")
    }
}
