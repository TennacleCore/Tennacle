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
    mavenLocal() // minestom-mechanics + archipelago when the sibling checkouts are absent
    maven("https://jitpack.io")
}

dependencies {
    val minestomVersion = "2026.08.16-26.2"
    val junitVersion = "6.0.3"

    implementation("net.minestom:minestom:$minestomVersion")
    implementation("io.github.term4:minestom-mechanics:0.2.0")
    implementation("io.github.term4:archipelago:0.2.0")
    implementation("io.github.term4:polyp-world:0.2.0") // the seam archipelago compiles against; the app provides it
    implementation("io.github.term4:barnacle:0.1.0")
    implementation("io.github.term4:grotto:0.1.0")
    implementation("io.github.term4:bloom:0.1.0")
    implementation("io.github.term4:canvas:0.1.0")
    implementation("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT") // local fork, bumped to 26.2
    implementation("dev.hollowcube:polar:1.16.0")
    compileOnly("it.unimi.dsi:fastutil:8.5.19") // javac reads PolarLoader signatures that reference it
    implementation("com.github.luben:zstd-jni:1.5.7-7")
    runtimeOnly("org.tinylog:tinylog-api:2.8.0-M1")
    runtimeOnly("org.tinylog:tinylog-impl:2.8.0-M1")
    runtimeOnly("org.tinylog:slf4j-tinylog:2.8.0-M1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:$junitVersion")
}

application {
    mainClass = "test.TestServer"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED") // polar's zstd-jni
}

tasks.test {
    useJUnitPlatform()
    // synchronous player spawns + relaxed thread asserts (like Minestom's Env); must be set before ServerFlag loads
    systemProperty("minestom.inside-test", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
