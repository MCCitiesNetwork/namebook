plugins {
    `java-library`
    `maven-publish`
}

java {
    withSourcesJar()
}

publishing {
    repositories {
        maven {
            name = "deploy"
            // Snapshots and releases live side by side on the same host; the version picks one.
            val snapshot = version.toString().endsWith("-SNAPSHOT")
            val defaultUrl = "https://maven.minecraftcitiesnetwork.com/" + if (snapshot) "snapshots" else "releases"
            url = uri(providers.gradleProperty("deployUrl").getOrElse(defaultUrl))
            credentials {
                username = providers.gradleProperty("deployUsername").orNull
                password = providers.gradleProperty("deployPassword").orNull
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                url.set("https://github.com/MCCitiesNetwork/namebook")
                scm {
                    url.set("https://github.com/MCCitiesNetwork/namebook")
                }
            }
        }
    }
}
