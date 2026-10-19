plugins {
    application
}

description = "The Tennacle server: boot, the dev tools, and the modules installed in order"

dependencies {
    implementation(project(":lobby"))
    runtimeOnly("org.tinylog:tinylog-api:2.8.0")
    runtimeOnly("org.tinylog:tinylog-impl:2.8.0")
    runtimeOnly("org.tinylog:slf4j-tinylog:2.8.0")

    testImplementation(testFixtures(project(":lobby")))
}

application {
    applicationName = "Tennacle"
    mainClass = "tennacle.server.TestServer"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED", // polar's zstd-jni
        // unpark the write thread on a send instead of letting it sleep half a tick on an empty queue.
        // ServerFlag reads it in its static initializer, so it has to be a JVM arg, not System.setProperty
        "-Dminestom.new-socket-write-lock=true",
        // a hot site's exception otherwise logs bare, no message and no stack
        "-XX:-OmitStackTraceInFastThrow",
        // C2 bug JDK-8385993 (fixed in 28, no backport): a reduced allocation merge came back null on deopt in
        // MotionTracker.tickFlowPush, 10-17 and again 10-18. Drop on a JDK with the fix; an unknown -XX flag stops the JVM
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:-ReduceAllocationMerges",
        // every JIT deopt, to logs/ beside tennacle.log, for a repeat of the above
        "-Xlog:deoptimization=debug:file=logs/deopt.log:time,tid:filecount=5,filesize=20m",
    )
}

// arenas/, data/ and tennacle.properties are read from the working directory: the repo root
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

// `run` holds the build outputs open, so a rebuild during a session either fails on a locked jar or feeds the
// live JVM half-new classes. installDist copies them, and nothing but installDist writes there again.
tasks.named<Sync>("installDist") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Hypixel's archived BedWars worlds plus the research markers, as arenas. The worlds are Hypixel's, so the
// output defaults under build/ and stays out of git
tasks.register<JavaExec>("bedwarsMaps") {
    description = "Builds BedWars arenas from -Parchive (Hypixel's map zips) and -Pmarkers; -Pout, -Pmaps optional"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "tennacle.dev.BedwarsMaps"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
    val archive = providers.gradleProperty("archive")
    val markers = providers.gradleProperty("markers")
    val out = providers.gradleProperty("out").orElse(rootProject.layout.projectDirectory.dir("arenas/bedwars").asFile.path)
    val maps = providers.gradleProperty("maps").orElse("")
    argumentProviders.add(CommandLineArgumentProvider { listOf(archive.get(), markers.get(), out.get(), maps.get()) })
}

tasks.register<JavaExec>("bedwarsFacing") {
    description = "Re-aims the spawns of the arenas in -Pdir (arenas/bedwars) away from their forge, worlds untouched"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "tennacle.dev.BedwarsMaps"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
    val dir = providers.gradleProperty("dir").orElse(rootProject.layout.projectDirectory.dir("arenas/bedwars").asFile.path)
    argumentProviders.add(CommandLineArgumentProvider { listOf("--reface", dir.get()) })
}

tasks.register<JavaExec>("arenaIcons") {
    description = "Lists each arena's map-menu icon in -Pdir (arenas/bedwars); -Pwrite=true stores the ones not hand-picked"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "tennacle.dev.ArenaIcons"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
    val dir = providers.gradleProperty("dir").orElse(rootProject.layout.projectDirectory.dir("arenas/bedwars").asFile.path)
    val write = providers.gradleProperty("write").orElse("false")
    argumentProviders.add(CommandLineArgumentProvider { if (write.get() == "true") listOf(dir.get(), "--write") else listOf(dir.get()) })
}

tasks.register<JavaExec>("roomExtract") {
    description = "Cuts the -Pkey region of -Psource (an arena) into -Pout (arenas/rooms.bncl) as the room variant -Pvariant"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "tennacle.dev.RoomExtract"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    workingDir = rootDir
    val source = providers.gradleProperty("source")
    val key = providers.gradleProperty("key").orElse("bedwars:lobby")
    val variant = providers.gradleProperty("variant").orElse("bedwars")
    val out = providers.gradleProperty("out").orElse(rootProject.layout.projectDirectory.file("arenas/rooms.bncl").asFile.path)
    argumentProviders.add(CommandLineArgumentProvider { listOf(source.get(), key.get(), variant.get(), out.get()) })
}
