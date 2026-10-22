plugins {
    java
}

group = "com.minecraftcitiesnetwork"

// The release workflow passes -PreleaseVersion=<tag without its leading v>, so the
// git tag is the single source of truth for a published build.
//
// Deliberately not named "version": Gradle already defines a project property by that
// name, so -Pversion behaves differently depending on whether a build script assigns
// it. A distinct name has no such ambiguity.
version = (findProperty("releaseVersion") ?: "1.0.0-SNAPSHOT").toString()

val targetJavaVersion = 25

java.toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))

repositories {
    mavenCentral()
    maven {
        name = "papermc-repo"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "minecraftcitiesnetwork-releases"
        url = uri("https://maven.minecraftcitiesnetwork.com/releases")
    }
    maven {
        name = "minecraftcitiesnetwork-snapshots"
        url = uri("https://maven.minecraftcitiesnetwork.com/snapshots")
    }
}

dependencies {
    compileOnly("org.jetbrains:annotations:26.0.2-1")
    testCompileOnly("org.jetbrains:annotations:26.0.2-1")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    test {
        useJUnitPlatform()
    }

    withType(JavaCompile::class) {
        options.release.set(targetJavaVersion)
        options.encoding = "UTF-8"
        options.isFork = true
        options.isDeprecation = true
    }
}
