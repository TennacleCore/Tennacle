# BedWars maps

These worlds are Hypixel's maps, from the Hypixel map archive, converted to Tennacle arenas by
`gradlew bedwarsMaps` (`tennacle.dev.BedwarsMaps`). Hypixel built them and holds every right in them; we claim
neither. Each arena names Hypixel as its author.

The markers come from our captures of Hypixel's games (`docs/bedwars-research.md`, section 15.4). An arena
tagged `bedwars-guessed` is one no capture has recorded: its spawns, forges, generators and zones are guesses.
A seasonal reskin with no game of its own takes its base map's captures: it keeps the base's beds and
generators.
The arenas sit under `arenas/bedwars/` so that the other modes' map pools, which read `arenas/` alone, never
load them.
