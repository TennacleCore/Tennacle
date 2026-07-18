# Tennacle

The full Archipelago + MinestomMechanics playground: the combined test server (`./gradlew run`) and the
cross-library bridge tests (`./gradlew test`).

Builds against the sibling `../MinestomMechanics` and `../Archipelago` checkouts when present (live sources,
no publish step); otherwise resolves the published/mavenLocal artifacts.

- `src/main/java/test/TestServer.java` - the server: shards + combat presets, TNT, replays, saves, Via/1.8 compat
- `src/main/java/test/ShardBridge.java`, `ReplayBridge.java` - the glue templates for using both libraries together

Licensed under [MIT](LICENSE).
