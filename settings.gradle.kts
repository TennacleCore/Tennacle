rootProject.name = "Tennacle"

// dev loop: build against the sibling checkouts when present - live sources, no publishToMavenLocal step;
// absent, the mavenLocal/Central artifacts resolve instead
if (file("../Polyp").exists()) {
    includeBuild("../Polyp") {
        dependencySubstitution {
            substitute(module("io.github.term4:polyp")).using(project(":"))
            substitute(module("io.github.term4:polyp-world")).using(project(":world"))
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
if (file("../Barnacle").exists()) {
    includeBuild("../Barnacle") {
        dependencySubstitution {
            substitute(module("io.github.term4:barnacle")).using(project(":"))
        }
    }
}
if (file("../Grotto").exists()) {
    includeBuild("../Grotto") {
        dependencySubstitution {
            substitute(module("io.github.term4:grotto")).using(project(":"))
        }
    }
}
if (file("../Bloom").exists()) {
    includeBuild("../Bloom") {
        dependencySubstitution {
            substitute(module("io.github.term4:bloom")).using(project(":"))
        }
    }
}
if (file("../Canvas").exists()) {
    includeBuild("../Canvas") {
        dependencySubstitution {
            substitute(module("io.github.term4:canvas")).using(project(":"))
        }
    }
}