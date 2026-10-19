# Tennacle

The app the libraries are built for: the combined server and the cross-library bridge tests
(`./gradlew test`).

Run it from an installed copy, not from the build outputs - `./gradlew run` holds them open, so a rebuild
mid-session either fails on a locked jar or hands the live JVM half-new classes:

```
./gradlew installDist
server/build/install/Tennacle/bin/Tennacle
```

Both the arena directory and the config file are read relative to the working directory, so start it from
the repository root.

Builds against the sibling `../Polyp`, `../Archipelago`, `../Barnacle`, `../Grotto`, `../Bloom`, `../Canvas`
and `../schem` checkouts when present (live sources, no publish step); otherwise resolves the
published/mavenLocal artifacts. `blocksandstuff-blocks` is a local fork and always comes from mavenLocal.

Modules, each over the ones before it; social, games and lobby each install through one class:

- `core` - what the rest stands on: `Mechanics` (systems + bridges), `Msg`, `PlayerPrefs`, the command registry,
  `Config` (system property, then `TENNACLE_*` environment, then `tennacle.properties`), `storage/` (what the server
  persists; the libraries persist nothing), and `ShardBridge`/`ReplayBridge`, the glue for using both libraries
- `social` - parties and who may invite whom (`SocialModule`)
- `games` - the match layer, matchmaking, spectating, replays, the looks and every mode (`GamesModule`)
- `lobby` - the lobby worlds, hotbar, NPCs, menus and settings (`LobbyModule`)
- `server` - `TestServer` (boot: installs the modules in that order) and the staff tools (`DevCommands`,
  `ShardCommands`, /gamemode, /lag, /enchant, ...), always registered behind `tennacle.staff.<command>`

Test harness: `core`'s test fixtures (`FakePlayer`, `HeadlessServerTest`), then `TestWiring` (games) and
`LobbyWiring` (lobby) for a module's tests over the ones below it.

Copy `tennacle.properties.example` to `tennacle.properties` to change the port, the bind address, the
directories or the proxy forwarding secret. Defaults bind loopback only; a public bind refuses to start
without a forwarding secret.

Licensed under [MIT](LICENSE).
