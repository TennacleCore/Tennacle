description = "The Tennacle server: the app every library here is built for, and the cross-library bridge tests"

// core, then social, games and lobby over it, and the server that installs them in that order
subprojects {
    apply(plugin = "java-library")
    group = "io.github.term4.tennacle" // apart from the composite's own :core projects
    version = "0.1.0"

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(25)
    }
    extensions.configure<BasePluginExtension> {
        archivesName = "tennacle-${project.name}"
    }

    repositories {
        mavenCentral()
        mavenLocal() // polyp + friends when the sibling checkouts are absent (and the local blocksandstuff fork)
    }

    val junitVersion = "6.1.3"
    dependencies {
        "compileOnly"("org.slf4j:slf4j-api:2.0.18") // minestom's runtime carries the binding
        "compileOnly"("it.unimi.dsi:fastutil:8.5.19") // javac reads PolarLoader signatures that reference it
        "testImplementation"("org.junit.jupiter:junit-jupiter-api:$junitVersion")
        "testRuntimeOnly"("org.junit.jupiter:junit-jupiter-engine:$junitVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:$junitVersion")
        "testRuntimeOnly"("org.tinylog:tinylog-api:2.8.0")
        "testRuntimeOnly"("org.tinylog:tinylog-impl:2.8.0")
        "testRuntimeOnly"("org.tinylog:slf4j-tinylog:2.8.0")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        workingDir = rootDir // arenas/ and data/ are read from the working directory, as the server reads them
        maxHeapSize = "2g" // one shared headless server across 50+ classes; gradle's 512m default runs out near the end
        // synchronous player spawns + relaxed thread asserts (like Minestom's Env); must be set before ServerFlag loads
        systemProperty("minestom.inside-test", "true")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
