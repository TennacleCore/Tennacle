# Tennacle

The full Archipelago + MinestomMechanics playground: the combined test server (`./gradlew run`) and the
cross-library bridge tests (`./gradlew test`).

Builds against the sibling `../MinestomMechanics` and `../Archipelago` checkouts when present (live sources,
no publish step); otherwise resolves the published/mavenLocal artifacts.

- `TestServer` - boot only; `Mechanics` (systems + bridges), `Playground` (world, test pads, kit, TNT item)
- `ShardCommands` / `ReplayCommands` / `DevCommands` - /shard, /replay, and the polyp dev toolset (/lag, /enchant, ...)
- `ShardBridge`, `ReplayBridge` - the glue templates for using both libraries together
- `Config` - boot settings: system property, then `TENNACLE_*` environment, then `tennacle.properties`
- `storage/` - what the server persists (the libraries persist nothing): accounts, match records, atomic writes

Copy `tennacle.properties.example` to `tennacle.properties` to change the port, the bind address, the
directories or the proxy forwarding secret. Defaults bind loopback only; a public bind refuses to start
without a forwarding secret.

Licensed under [MIT](LICENSE).
