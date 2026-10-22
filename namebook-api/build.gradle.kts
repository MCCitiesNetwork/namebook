plugins {
    `java-library`
    `namebook-conventions`
    `namebook-publish`
}

publishing {
    publications.named<MavenPublication>("maven") {
        pom {
            description.set("Public API for looking up Minecraft player names and UUIDs through Namebook")
        }
    }
}

dependencies {
    // Only NamebookFinder touches the server API; the interface itself is plain Java.
    compileOnly("io.papermc.paper:paper-api:26.3.build.211-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.211-stable")
    testImplementation("org.mockito:mockito-core:5.23.0")
}
