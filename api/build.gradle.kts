plugins {
    application
}

description = "The public API: read-only JSON over the database, for players' own bots and sites. Its own process"

dependencies {
    implementation(project(":postgres"))
    runtimeOnly("org.tinylog:tinylog-api:2.8.0")
    runtimeOnly("org.tinylog:tinylog-impl:2.8.0")
    runtimeOnly("org.tinylog:slf4j-tinylog:2.8.0")

    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
}

application {
    applicationName = "TennacleApi"
    mainClass = "tennacle.api.service.ApiServer"
}

// tennacle.properties is read from the working directory, as the game server reads it
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
