plugins {
    `java-library`
    `java-test-fixtures`
}

description = "The lobbies: their worlds, hotbar, NPCs, menus, settings and chat rooms"

dependencies {
    api(project(":games"))

    testFixturesApi(testFixtures(project(":games")))
}
