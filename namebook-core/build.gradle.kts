plugins {
    `java-library`
    `namebook-conventions`
}

dependencies {
    api(project(":namebook-api"))
    api("org.mybatis:mybatis:3.5.19")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.6")
    implementation("com.minecraftcitiesnetwork:plugin-infrastructure:1.0.0-SNAPSHOT")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.testcontainers:testcontainers-mariadb:2.0.1")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.2")
}
