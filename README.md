# Tennacle

The app the libraries are built for: the combined server and the cross-library bridge tests
(`./gradlew test`).

Run it from an installed copy, not from the build outputs - `./gradlew run` holds them open, so a rebuild
mid-session either fails on a locked jar or hands the live JVM half-new classes:

```
./gradlew installDist
build/install/Tennacle/bin/Tennacle
```

Both the arena directory and the config file are read relative to the working directory, so start it from
the repository root.

Builds against the sibling `../Polyp`, `../Archipelago`, `../Barnacle`, `../Grotto`, `../Bloom`, `../Canvas`
and `../schem` checkouts when present (live sources, no publish step); otherwise resolves the
published/mavenLocal artifacts. `blocksandstuff-blocks` is a local fork and always comes from mavenLocal.

- `TestServer` - boot only; `Mechanics` (systems + bridges), `Playground` (world, test pads, kit, TNT item)
- `ShardCommands` / `ReplayCommands` / `DevCommands` - /shard, /replay, and the polyp dev toolset (/lag, /enchant, ...)
- `ShardBridge`, `ReplayBridge` - the glue templates for using both libraries together
- `Config` - boot settings: system property, then `TENNACLE_*` environment, then `tennacle.properties`
- `storage/` - what the server persists (the libraries persist nothing): accounts, match records, atomic writes

Copy `tennacle.properties.example` to `tennacle.properties` to change the port, the bind address, the
directories or the proxy forwarding secret. Defaults bind loopback only; a public bind refuses to start
without a forwarding secret.

Licensed under [MIT](LICENSE).
