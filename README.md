# Tennacle

The full Archipelago + MinestomMechanics playground: the combined test server (`./gradlew run`) and the
cross-library bridge tests (`./gradlew test`).

Builds against the sibling `../MinestomMechanics` and `../Archipelago` checkouts when present (live sources,
no publish step); otherwise resolves the published/mavenLocal artifacts.

- `TestServer` - boot only; `Mechanics` (systems + bridges), `Playground` (world, test pads, kit, TNT item)
- `ShardCommands` / `ReplayCommands` / `DevCommands` - /shard, /replay, and the polyp dev toolset (/lag, /enchant, ...)
- `ShardBridge`, `ReplayBridge` - the glue templates for using both libraries together

Licensed under [MIT](LICENSE).
