plugins {
    `java-library`
    `java-test-fixtures`
}

description = "What every Tennacle module stands on: the libraries, the systems handle, messages, preferences, storage and the command registry"

dependencies {
    api("net.minestom:minestom:2026.09.12-26.2")
    api("io.github.term4:polyp:0.3.1")
    api("io.github.term4:archipelago:0.2.0")
    api("io.github.term4:polyp-world:0.3.1") // the seam archipelago compiles against; the app provides it
    api("io.github.term4:barnacle:0.2.0")
    api("io.github.term4:grotto:0.1.0")
    api("io.github.term4:bloom:0.1.0")
    api("io.github.term4:canvas:0.1.0")
    api("org.everbuild.blocksandstuff:blocksandstuff-blocks:1.10.2-SNAPSHOT") // local fork, bumped to 26.2
    api("dev.hollowcube:polar:1.16.0")
    api("com.github.luben:zstd-jni:1.5.7-16")

    testFixturesApi("org.junit.jupiter:junit-jupiter-api:6.1.3")
}
