# Bed Wars maps: the archive against live Hypixel

The Bed Wars arenas are built from the Hypixel Map Archive's worlds (`gradlew :server:bedwarsMaps`), and Hypixel has
edited some maps since those were saved. This lists every difference the captures through 2026-09-30 show, what our
arenas do about each one today, and what to check once the maps are downloaded again from live Hypixel. A fresh
download fixes all of them at once; until then the arenas work around them as below.

## Where Hypixel changed a map

| Map | What live Hypixel has | Evidence | Our arena today |
|---|---|---|---|
| Lucky Rush | Every base sits 6 blocks further in and 2 blocks nearer its partner base than the archive's, bed and all. The four diamond islands sit 6 blocks further in on both axes. Islands are closer together on Hypixel, so our bridges are longer. | All 8 beds seen set at game start. Hypixel's re-sent map blocks fit the archive at those shifts (Red -2, 0, -6 and Gray -6, 0, -2; the diamond islands 6, 6). Captured keepers stood inside the archive's walls. Captured diamonds stood 6 blocks off their islands' middle. | Keepers, spawns and forges carried onto the archive's bases by each bed's shift. Diamond generators centered on their islands. Island distances stay the archive's. |
| Swashbuckle | All four beds stand a block forward of the archive's. The buildings are unchanged. Barriers the archive lacks behind the Yellow base (30 cells seen). | Beds seen at game start. 44 re-sent blocks match the buildings as they are. Barriers re-sent at x -94..-81, y 64..70, z -5..6. | Beds moved to Hypixel's spots. The 30 barriers added. |
| Lighthouse | The Blue, Yellow, White and Gray bases were rebuilt: the keeper pair stands a block to the side of the archive's keeper spots, Blue's chest and ender chest sit a block aside, yet a log by Blue's bed is where the archive has it and a sandstone block is where the archive's mirror image would put it. No single shift or mirror of the archive's base reproduces them. Beds, forges and spawns are where the archive has them. | Captured keepers and the archive's barrier-marked keeper spots. The blocks Hypixel re-sent around Blue. | Both keepers on those bases moved a block onto the archive's spots, keeping the half block every Lighthouse keeper stands off its spot. |
| Lectus | Each item shop keeper stands on a full-height floor where the archive has a half slab. Each forge drops where the archive has a column of stone, hopper and fire. | Captured keepers at 66.06 over the archive's slab at 65.5. Captured forge drops inside the column. | Item shop keepers lowered 0.56 onto the slab. Forges moved a block back, beside the column. |
| Harvesting | Each forge platform sits 2 blocks deeper into the back wall. | Forge drops rest at y 79.5 at (0.5, -97.5). Wood slabs re-sent at (0, 79, -98) and (0, 79, -99), where the archive has planks and stone. | Forges moved a block toward the bed, onto the archive's platform. |
| Crogorm, Mirage, Invasion | Barriers the archive lacks: Crogorm 8 cells near (-87, 90, 47) and (-73, 91, 46), Mirage 8 near (-42, 75, -94), Invasion 2 near (48, 100, -49). | Barriers re-sent there. | Added. |

## Smaller differences, not in the arenas

Each line is a cluster of cells where Hypixel re-sent a different block than the archive holds. Only the cells players
touched are known, so these stay out of the arenas until a download shows the whole change.

| Map | Where (x, y, z) | Hypixel has |
|---|---|---|
| Amazon | -39..-37, 64..65, -103..-102 | stone where the archive has air (4 cells) |
| Aquarium | 0..1, 85..86, 69 | stone where the archive has air (3) |
| Crimson | -30..-27, 76, -76..-71 | brown mushrooms where the archive has air (4) |
| Deadwood | 29..31, 80..84, 93..95 | glass panes where the archive has air (8), wood slabs where it has air (3) |
| Frogiton | 100..101, 62..63, 0 | dirt where the archive has stained clay (3) |
| Invasion | 40..43, 94..99, -45..-43 | double slabs where the archive has air (5), a slab where it has air (1) |
| Ironclad | -26..-23, 67..68, -100..-99 | stone where the archive has air (3) |
| Katsu | 0, 67..68, -80..-79 | stone where the archive has stone bricks (2) and wool (1) |
| Mirage | -47, 72..73, -102..-101 | double slabs where the archive has air (3) |
| Orchestra (v2 in the archive) | -91..-87, 81, -37..-36 | double slabs where the archive has air (2), a slab where it has a coal block (1) |
| Paladin | 70..73, 81..82, 12 | double slabs where the archive has air (5) |
| Temple | 36..37, 60, 39..42 | leaves where the archive has air (5) |
| Urban Plaza | -77, 68..74, 35..37 | leaves where the archive has air (4), stone where it has dirt (1) |

296 more cells differ one or two at a time across 55 maps. They are listed with their exact blocks in
`research/bedwars/live-blocks-2026-09-30.json` (`[x, y, z, live id, archive id, archive meta]` per map).

## How these were found

All scripts are in `minemen-capture/research/bedwars`.

- `live_beds.py`: each game's start sets every team's bed and clears an empty team's to air, so every live bed is
  known. `bed_drift.py` compares them with the archive's.
- `base_fit.py`: captured keepers, spawns, forges and generators against the archive's blocks.
- `live_fit.py` and `map_diff.py`: when a dig or a refused placement touches a map block, Hypixel re-sends it, so a
  re-sent block is the live map showing through. Only blocks the shop can't sell count. Hypixel's own structures
  (the pregame room, island toppers) are dropped: they arrive many to a tick or at the same cells on any map. Some
  captured games let players break map blocks and place what they mined (one capture carried 51 cobblestone); those
  games only count blocks no pickaxe returns as themselves (stone, leaves, glass panes, barriers). Air never counts:
  a player's block broken where the capture never saw it placed looks the same.
- Two conventions hold on every captured map and caught the rest: the archive marks keeper spots with a barrier one
  to three blocks over the keeper's feet, and a generator over a pad of its own block stands at the pad's middle.

Coverage is only what the captures touched: across 72 maps, 4,847 touched cells agree with the archive and 451 differ.

## After a fresh download

1. Download each map's world from a live game. The maps above come first.
2. Point `map_markers.py` and `gradlew :server:bedwarsMaps -Parchive=...` at the new worlds and rebuild.
3. Re-run `run_extractors.py`, then `bed_drift.py`, `base_fit.py` and `map_diff.py`. Nothing should drift, every
   captured point should fit as captured, and no cluster should remain.
4. The arena's workarounds then do nothing on their own: no bed drifts to carry, no bed to move, no point to
   nudge, every generator already mid-pad, no barrier missing. Check the markers' `carried`, `bed archive`,
   `fitted` and `live blocks` fields are gone from every map.
