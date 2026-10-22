plugins {
    `java-library`
    `namebook-conventions`
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.3.1"
}

dependencies {
    implementation(project(":namebook-core"))
    compileOnly("io.papermc.paper:paper-api:26.3.build.211-stable")
    implementation("org.incendo:cloud-paper:2.0.1")
    implementation("org.spongepowered:configurate-yaml:4.2.0")
    // Shared helpers (MessageContainer, ComponentSerializer, SchemaMigrator). Deliberately NOT
    // relocated, matching the other plugins that share it.
    implementation("com.minecraftcitiesnetwork:plugin-infrastructure:1.0.0-SNAPSHOT")

    testImplementation("io.papermc.paper:paper-api:26.3.build.211-stable")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
}

// --- Development database (see compose.dev.yml) ---------------------------------
//
// Everything the task actions touch is resolved HERE, at configuration time, and
// captured as plain Files/Strings so the tasks stay configuration-cache clean.

val devComposeFile = rootProject.layout.projectDirectory.file("compose.dev.yml").asFile
val devDbConfigFile = layout.projectDirectory.file("run/plugins/Namebook/database.yml").asFile
val devDbUrl = "mariadb://localhost:3306/namebook"
val devDbUser = "namebook"
val devDbPassword = "namebook"

// Builds a `docker compose` command line. Deliberately a PURE function returning a
// list: a task action defined inside a script-level function captures the script
// itself, which the configuration cache cannot serialize. Each task registers its
// own doLast below, capturing only local values.
fun dockerCompose(vararg args: String): List<String> =
        listOf("docker", "compose", "-f", devComposeFile.absolutePath) + args

// Message used when the compose invocation fails, so the cause is obvious rather
// than a bare non-zero exit code.
val dockerHint = "Is Docker installed and running? " +
        "The dev server needs it for the MariaDB in ${devComposeFile.name}."

val startDevDb = tasks.register<Exec>("startDevDb") {
    group = "namebook dev"
    description = "Starts the development MariaDB and points run/plugins/Namebook/database.yml at it."
    // --wait blocks until the healthcheck passes, so the server never races a
    // MariaDB whose port is open but which cannot yet serve queries.
    commandLine(dockerCompose("up", "-d", "--wait"))
    isIgnoreExitValue = true
    val result = executionResult
    val hint = dockerHint
    val configFile = devDbConfigFile
    val url = devDbUrl
    val user = devDbUser
    val password = devDbPassword
    doLast {
        val exit = result.get().exitValue
        if (exit != 0) {
            throw GradleException("`docker compose up` exited with $exit. $hint")
        }
        // Only fill in a missing or blank config. Never clobber real credentials
        // someone has pointed at their own database.
        val existing = if (configFile.isFile) configFile.readText() else ""
        // A url that is empty, or only quotes and spaces, counts as missing.
        val hasUrl = Regex("""^[ \t]*url:[ \t]*(.*)$""", RegexOption.MULTILINE).findAll(existing)
                .any { it.groupValues[1].trim().trim('\'', '"').isNotBlank() }
        if (hasUrl) {
            logger.lifecycle("Dev database up; leaving existing ${configFile.name} untouched.")
        } else {
            configFile.parentFile.mkdirs()
            configFile.writeText(
                    """
                    url: '$url'
                    username: '$user'
                    password: '$password'
                    """.trimIndent() + System.lineSeparator()
            )
            logger.lifecycle("Dev database up; wrote ${configFile.name} pointing at $url")
        }
    }
}

tasks.register<Exec>("stopDevDb") {
    group = "namebook dev"
    description = "Stops the development MariaDB, keeping its data."
    commandLine(dockerCompose("down"))
    isIgnoreExitValue = true
    val result = executionResult
    val hint = dockerHint
    doLast {
        val exit = result.get().exitValue
        if (exit != 0) {
            throw GradleException("`docker compose down` exited with $exit. $hint")
        }
    }
}

tasks.register<Exec>("resetDevDb") {
    group = "namebook dev"
    description = "Stops the development MariaDB and DELETES its data volume."
    // Namebook re-runs its migrations on enable, so the next start rebuilds the schema.
    commandLine(dockerCompose("down", "-v"))
    isIgnoreExitValue = true
    val result = executionResult
    val hint = dockerHint
    doLast {
        val exit = result.get().exitValue
        if (exit != 0) {
            throw GradleException("`docker compose down -v` exited with $exit. $hint")
        }
    }
}

tasks {

    test {
        val byteBuddyAgent = configurations.testRuntimeClasspath.get().files.find { it.name.contains("byte-buddy-agent") }
        if (byteBuddyAgent != null) {
            jvmArgs("-javaagent:$byteBuddyAgent")
        }
    }

    shadowJar {
        // com.minecraftcitiesnetwork.namebook.api is deliberately NOT relocated: other plugins
        // compile against those names and load them from this jar at runtime.
        val base = "com.minecraftcitiesnetwork.namebook.libraries"
        relocate("org.mariadb", "${base}.org.mariadb")
        relocate("org.mybatis", "${base}.org.mybatis")
        relocate("org.apache.ibatis", "${base}.org.apache.ibatis")
        relocate("org.spongepowered", "${base}.org.spongepowered")
        relocate("org.yaml", "${base}.org.yaml")
        relocate("io.leangen.geantyref", "${base}.io.leangen.geantyref")
        relocate("org.jetbrains.annotations", "${base}.org.jetbrains.annotations")
        relocate("org.intellij.lang", "${base}.org.intellij.lang")
        relocate("org.jspecify", "${base}.org.jspecify")
        relocate("net.kyori.option", "${base}.net.kyori.option")
        relocate("org.incendo.cloud", "${base}.org.incendo.cloud")
        relocate("com.fasterxml.jackson", "${base}.com.fasterxml.jackson")
        mergeServiceFiles()
    }

    processResources {
        val projectVersion = version
        // Declared as an input so a version bump invalidates the task; otherwise Gradle reuses
        // the previously expanded paper-plugin.yml and ships the previous version string.
        inputs.property("version", projectVersion)
        filesMatching("paper-plugin.yml") {
            expand("version" to projectVersion)
        }
    }

    runServer {
        dependsOn(startDevDb)
        minecraftVersion("26.3")
    }
}
