plugins {
    java
    application
}

description = "The full Archipelago + MinestomMechanics playground server and cross-library bridge tests"
group = "io.github.term4"
version = "0.1.0"
java.toolchain.languageVersion = JavaLanguageVersion.of(25)

repositories {
    mavenCentral()
    mavenLocal() // polyp + friends when the sibling checkouts are absent (and the local blocksandstuff fork)
}

dependencies {
    val minestomVersion = "2026.08.28-26.2"
    val junitVersion = "6.1.3"

    implementation("net.minestom:minestom:$minestomVersion")
    compileOnly("org.slf4j:slf4j-api:2.0.18") // minestom's runtime carries the binding
    implementation("io.github.term4:polyp:0.3.1")
    implementation("io.github.term4:archipelago:0.2.0")
    implementation("io.github.term4:polyp-world:0.3.1") // the seam archipelago compiles against; the app provides it
    implementation("io.github.term4:barnacle:0.2.0")
    implementation("io.github.term4:grotto:0.1.0")
    implementation("io.github.term4:bloom:0.1.0")
    implementation("io.github.term4:canvas:0.1.0")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT") // local fork, bumped to 26.2
    implementation("dev.hollowcube:polar:1.16.0")
    compileOnly("it.unimi.dsi:fastutil:8.5.19") // javac reads PolarLoader signatures that reference it
    implementation("com.github.luben:zstd-jni:1.5.7-16")
    runtimeOnly("org.tinylog:tinylog-api:2.8.0")
    runtimeOnly("org.tinylog:tinylog-impl:2.8.0")
    runtimeOnly("org.tinylog:slf4j-tinylog:2.8.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

application {
    mainClass = "tennacle.TestServer"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED") // polar's zstd-jni
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g" // one shared headless server across 50+ classes; gradle's 512m default runs out near the end
    // synchronous player spawns + relaxed thread asserts (like Minestom's Env); must be set before ServerFlag loads
    systemProperty("minestom.inside-test", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
