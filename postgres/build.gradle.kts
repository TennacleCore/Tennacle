plugins {
    `java-library`
}

description = "The PostgreSQL stores behind core's storage interfaces, and the one-time import from the data files"

dependencies {
    api(project(":core"))
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")

    testImplementation("io.zonky.test:embedded-postgres:2.1.0") // a real server per test run, no Docker
}
