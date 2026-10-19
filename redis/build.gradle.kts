plugins {
    `java-library`
}

description = "The Redis side of core's network seams: the bus, presence and rate limits shared across processes"

dependencies {
    api(project(":core"))
    implementation("redis.clients:jedis:5.2.0")

    testImplementation("com.github.fppt:jedis-mock:1.1.4") // a Redis speaking server in the test JVM
}
