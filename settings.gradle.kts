rootProject.name = "Tennacle"

// dev loop: build against the sibling checkouts when present - live sources, no publishToMavenLocal step;
// absent, the mavenLocal/Central artifacts resolve instead
if (file("../Polyp").exists()) {
    includeBuild("../Polyp") {
        dependencySubstitution {
            substitute(module("io.github.term4:minestom-mechanics")).using(project(":"))
        }
    }
}
if (file("../Archipelago").exists()) {
    includeBuild("../Archipelago") {
        dependencySubstitution {
            substitute(module("io.github.term4:archipelago")).using(project(":archipelago"))
        }
    }
}
