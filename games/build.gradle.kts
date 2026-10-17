plugins {
    `java-library`
    `java-test-fixtures`
}

description = "The games: the match layer, matchmaking, spectating, replays, the looks and every mode"

dependencies {
    api(project(":social"))

    testFixturesApi(testFixtures(project(":core")))
}
