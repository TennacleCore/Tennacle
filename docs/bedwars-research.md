# BedWars research

Status: RESEARCH, 2026-09-23. Nothing is built. This is the fact base for sketching a Hypixel-parity BedWars
mode: how Hypixel runs the game, what is settled, what only a server capture can settle, and how the pieces
land on Polyp, Grotto and Tennacle.

Confidence marks used below:
- **cap**: seen on the wire in our own Hypixel captures. Strongest.
- **off**: Hypixel staff patch notes (dated) or the public API.
- **wiki**: Fandom or NamuWiki. Community edited; Fandom's page carries an Outdated banner.
- **comm**: forum posts, guides, client-mod source. Unverified.
- **CAPTURE**: no source settles it. Section 13 turns these into a capture plan.

## 1. Sources

- **The official Hypixel Wiki is gone.** It only ever covered SkyBlock and closed in July 2026, so no
  official wiki page exists for any BedWars fact.
- **Patch notes**, 2017 to 2026: the Bed Wars Patch Notes forum
  (https://hypixel.net/forums/bed-wars-patch-notes.262/). Authoritative for their date; later posts often
  supersede earlier ones silently.
- **Public API** without a key: `/v2/resources/games`, `/achievements`, `/quests`, `/challenges`. The API
  documents no stat field names; those come from 16 real `/v2/player` responses committed to GitHub,
  checked against Statsify, hypixel-api-reborn and coral.
- **Fandom** (https://hypixel.fandom.com/wiki/Bed_Wars): the main page, its shop and upgrades GUI templates
  (January 2023, the best layout and lore source even where prices are stale), and 215 map pages with build
  limits.
- **NamuWiki** (Korean, read from a 2026-06-01 Wayback snapshot): the most complete generator timings, and
  its arithmetic checks out against our capture.
- **Client mods** that parse live Hypixel output: exact strings and timer tables as players see them today.
- **Our captures** (`Desktop/minemen-capture/captures`):
  - `2026-08-20T19-42-44-801Z_scoreboard`: three Hypixel Solo games on 1.8.9, game version v1.11.2.
    Pregame and in-game sidebars and every chat line. No titles, sounds, entities or windows.
  - `hypixelbf`: a Bed Rush Duel, 2026-09-07. Board, titles with fade timings, death lines.
  - `hpxlbw`: a damage capture inside a Hypixel BedWars game, 2026-09-01 (health, armor, blocking, arrows,
    crits on both sides; enchanted armor noted).
- **Prior art** (section 14): Swofty's Minestom recreation inside the local HypixelSkyBlock fork (AGPL-3.0)
  and ScreamingSandals BedWars (LGPL-3.0). Facts and design lessons only, never code.

Hypixel balances BedWars around 1.8 combat for every client version (off, v1.10).

## 2. Modes

| Mode | API key | Teams x players | Teams | Price group | Countdown starts at |
|---|---|---|---|---|---|
| Solo | `BEDWARS_EIGHT_ONE` | 8 x 1 | Red Blue Green Yellow Aqua White Pink Gray | S/D | 7/8, 20 s (cap) |
| Doubles | `BEDWARS_EIGHT_TWO` | 8 x 2 | same eight | S/D | 14 (wiki) |
| 3v3v3v3 | `BEDWARS_FOUR_THREE` | 4 x 3 | Red Blue Green Yellow | 3s/4s | 11 (wiki) |
| 4v4v4v4 | `BEDWARS_FOUR_FOUR` | 4 x 4 | same four | 3s/4s | 14 (wiki) |
| 4v4 | `BEDWARS_TWO_FOUR` | 2 x 4 | Red Blue | 3s/4s, no obsidian | 7 (wiki) |

- **Overall stats are the sum of these five** (checked in 16 of 16 real players). Dreams, Castle and
  tournaments are separate.
- **4v4** has no dragons; a world border shrinks toward the end and damages players outside it (off, v1.6).
  Its event times and border numbers: not being captured (our play).
- **Dreams** rotate weekly, one at a time: Rush (maxed generators, three-layer bed defense), Ultimate
  (kits), Castle (40v40, three beds a side), Voidless, Armed (guns), Lucky Blocks, Swappage, One Block,
  seasonal Underworld. Separate stats.
- **Duels** since 2025-08-13, stored under `stats.Duels`: Bed Wars Duel (1v1, the full economy, skips the
  Level II generator upgrades so the first tier-up is Diamond III at 18:00, wiki) and Bed Rush Duel (two
  islands, starter gear, premade defenses, 10:00 limit, cap). Tennacle's bedfight hypixel look is built
  from the Bed Rush Duel capture.
- **Private games** (MVP++ only) expose modifiers, which read as a ready list of rule knobs: bed
  instabreak, disable block protection, event time multiplier, health buff, low gravity, max team
  upgrades, one hit one kill, respawn time (1, 5 or 10 s), speed, no diamonds, no emeralds (API
  `privategames` object). `Event Times: 4x - Fastest` (cap) runs every event at a quarter of its time: tiers
  at 1:30, 3:00, 4:30 and 6:00, Bed Gone 7:30, Sudden Death 10:00, Game End 12:30. The Bed Gone warning still
  comes 5 real minutes early, the second dragons 5 real minutes after Sudden Death, and generator and forge
  rates are untouched.
- **Challenges**: 30 opt-in rule restrictions for core modes (no team upgrades, no utilities, slow
  generator, knockback stick only, no swords, archer only, no sprint, capped resources, and so on), plus 9
  from 2026. Also rule-shaped; a later concern.

### Team letters and colors

| Team | Sidebar letter | Color |
|---|---|---|
| Red | R | red |
| Blue | B | blue |
| Green | G | green |
| Yellow | Y | yellow |
| Aqua | A | aqua |
| White | W | white |
| Pink | P | light_purple |
| Gray | **S** | dark_gray |

Order and letters are cap (Gray is S because G is taken). Chat tags read `[RED]`, `[PINK]` (cap); the rest
follow the pattern. Leather RGB per team, all captured and carried by Canvas's `Tint.LEATHER_OF`: Red
`#FF0000`, Blue `#0000FF`, Green `#48CC18`, Yellow `#FFFF00`, White `#FFFFFF`, Aqua `#00FFFF`, Gray `#808080`,
Pink `#EF83A4`. 1.8 clients render every bed red; 1.12+ see team-colored beds (wiki).

## 3. Match flow

### 3.1 Pregame

- Players wait in a pregame area away from the bases and are teleported to their base at the start.
- Board (cap): date and server id, `Map: <name>`, `Players: n/8`, `Waiting...` or `Starting in 20s`,
  `Mode: Solo`, `Version: v1.11.2`, site line. Title BED WARS. `Starting in` counts down a second at a time,
  white with the seconds green (cap). Ours (2026-09-28): the same.
  Hypixel threads emoji characters through every board line, pregame and in game, which a 1.8 client draws
  as nothing; ours leaves them out.
- Boss bar (cap, public): a wither named yellow bold `Playing `, white bold `BED WARS `, yellow bold `on `
  and bold `MC.HYPIXEL.NET`, that last word turning aqua, white and green about every 0.3 s.
- Join and quit lines: `<name> has joined (7/8)!`, `<name> has quit!` (cap). Other players' names are
  obfuscated in pregame since 2024-08-28 (off).
- Countdown (cap): at the threshold `The game starts in 20 seconds!`, then 10, 5, 4, 3, 2, 1. The number
  is yellow at 20, gold at 10, red from 5. Falling below the threshold prints
  `We don't have enough players! Start cancelled.` A restarted countdown is 10 s (wiki). The last five
  seconds also title the digit (5 and 4 yellow, 3 to 1 red; fade 0, stay 20, out 20) and play `note.hat`
  at volume 20, pitch 1.0 each second (Bed Rush Duel capture).
- Parties share a team; a party member leaving in pregame sends the party back to the lobby (off).
- A public queue and a private game differ (our play). Public: no team choice, the names of players outside
  your party hidden (a party no bigger than the mode's team size), and nothing about teams shown until the
  game has filled. Private: the Team Selector and everyone's names. We join a waiting game on the map, as
  Hypixel does (decided 2026-09-27).
- Public pregame (cap, 2026-09-27: Solo on Deadwood alone, Doubles on Rooftop with a party of two):
  - Names: you and your party show as yourselves, the join line aqua (`Term4 has joined (6/16)!`, the
    party joining together). Everyone else gets a random string of letters and digits, a fresh one each,
    under the obfuscation code: gray obfuscated `mnDmjLi has joined (6/8)!` in chat, and a tab entry named
    `§kmnDmjLi`. No team shows anywhere until the start.
  - The countdown began one player short of full both times, about a second after the join that made it:
    at 7 of 8 in Solo and 15 of 16 in Doubles. Filling up mid-count shortened nothing (16 of 16 at 14 s
    left, 8 of 8 at 1 s).
  - The count: chat at 20, 10 and 5 to 1, each with `note.hat` at volume 20; titles at 10 (green) and 5 to 1,
    with an empty title sent each second from 9 to 6. The board reads `Starting in Ns` throughout.
  - At the start the real names replace the random ones in the tab, the team board appears, and the party
    shares a team (both on Aqua).
  - Grotto's `Countdown` waits for `START_AT` seated players and stops short below it, the look saying so
    (2026-09-27); the pregame look and `/play bedwars` come with the mode.
  - Ours, from the first playtest (2026-09-28): the board's `Players` row counts for everyone as seats come
    and go (Grotto's scoreboard re-reads every row on a join or a leave); `<name> has joined (n/N)!` and
    `<name> has quit!` in aqua while the game fills, through the look (`Look.arrival`, `Look.quit`); a seat left
    while filling is freed, not held, so no rejoin is offered and the leave line names no one but the mode; a
    party waits together and one member out takes the rest (`PartyExit`); nothing is placed or broken before
    the start, beds included (`Building`, `Beds` read `Match.live`); `/play <mode> [map]` names the map, a
    filling game on it joined first; the mid holograms count to the second drop from the start, the first
    coming at once as captured. A lowered `flow.countdown.start-at` starts the count on the spot (Grotto), the
    two-player test's way in. Hidden names for strangers are still to come.
- Soft matchmaking by BedWars level (brackets 1-10, 10-50, 50+, opened to all after 30 s waiting, off).

### 3.2 Start

- Banner (cap): a bold green bar of 64 `▬`, a centered bold `Bed Wars`, four centered lines telling you to
  protect your bed, destroy the enemy beds and collect Iron, Gold, Emerald and Diamond for upgrades, then
  the closing bar. Ours (2026-09-28): a game's banner heads with the mode's `banner` (`Bed Wars`, whatever
  the mode) and names no opponent; a duel's keeps `<Mode> Duel` and its opponent line.
- Starting kit: wooden sword and compass; full team-dyed leather armor, unbreakable, helmet with Aqua
  Affinity I (off, v0.4; comm 2020). The compass, green `Compass ` + gray `(Right Click)`, always comes in
  inventory slot 17, the main inventory's top right (cap: 298 sends in 25 sessions, the other 2 re-sending
  it to the hotbar slot a player had moved it to); the Hotbar Manager has no compass category. Right-clicking
  it opens the compass menu (6.2).
- The XP bar shows BedWars level progress in game (off, v1.6).
- Spawn protection at the start: assumed 3 s, like respawn invincibility (our play; not worth a capture).
- Hunger and regeneration (the user, 2026-09-28): food never drains and health comes back on its own. Ours:
  `hunger/exhaustionScale` 0 and `hunger/naturalRegen` on in the ruleset.

### 3.3 Timeline (core modes)

| At | Event | Sidebar label | Confidence |
|---|---|---|---|
| 0:00 | start, generators at tier I | `Diamond II in 6:00` | cap |
| 6:00 | diamond generators to tier II | Diamond II | cap |
| 12:00 | emerald generators to tier II | Emerald II | cap |
| 18:00 | diamond generators to tier III | Diamond III | off, wiki, 2026 client mods |
| 24:00 | emerald generators to tier III | Emerald III | same |
| 30:00 | every remaining bed destroyed | Bed Gone | same |
| 40:00 | Sudden Death: dragons | Sudden Death | same |
| 45:00 | a second dragon per alive team | none | off 2025-01-29, broken until 2026-05-20 |
| 50:00 | game ends | Game End | same |

- The sidebar shows only the next event, one line: white `<Event> in `, green `M:SS` (cap). It redraws at
  least once a second.
- Tier-up chat (cap): aqua `Diamond Generators`, yellow `have been upgraded to Tier`, red numeral; the
  emerald and tier III lines follow it (cap, 2026-09-24).
- Bed Gone: all beds go at once. At 25:00 a red `All beds will be destroyed in 5 minutes!` in chat, with no
  title or sound (cap, three games). At 30:00 (cap, Waterfall 2026-09-25): a clearing title, then (5/35/10)
  red `BED DESTROYED!` over a plain `All beds have been destroyed!`, the same line bold red in chat, and
  `mob.wither.death` (volume 1, pitch 1.0) at the player.
- Sudden Death: one ender dragon per team alive at 40:00, plus one more each at 45:00. Dragons break every
  block type, obsidian and end stone included, and hunt enemy players (off, v0.6). Boss bar for 1.9+ clients
  (off 2022). At 40:00 (cap, Waterfall 2026-09-25, two teams alive): red `Sudden Death` as a title (10/30/10,
  no subtitle), then per team red `SUDDEN DEATH: `, gold `+`, aqua `1 ` and `<Team> Dragon!` in the team
  color, with `mob.enderdragon.growl` (volume 1, pitch 1.0) at the player. Each dragon spawns 15 blocks over
  its team's spawn point, named `<Team> Dragon` in the team color, health 200. It climbs from rest, then flies
  the whole map at about a block a tick (90% of steps under 1.34, peak 2.2) between y 66 and 120, taking map
  and placed blocks alike: 5,900 blocks went in two and a half minutes. An enemy dragon's push (cap,
  `research/bedwars/dragon_knockback.py`) goes straight away from it, 0.4 up, harder the closer it is: 2.61
  blocks a tick across at 1.5 blocks, vanilla's wing push of 4 over the distance to within 0.01, and 0.4 to
  2.2 at 4 to 9 blocks, rough distances since a dragon moves a block a tick. Its bite is vanilla's 10, scaled
  for Easy to 6, through armor: 4.32 in plain leather, 6 x 0.72 exactly. Our own team's dragon never hurt or
  pushed us, though it passed within 0.7 to 6 blocks 15 times (cap, five games). Hits on a dragon (cap,
  `research/bedwars/creature_hits.py`): the head takes the hit in full and the other parts a quarter plus 1,
  as in vanilla, but Sharpness does not count: a Sharpness II wooden sword did 5 to the head and 2.25 to the
  body twice each, where vanilla gives 7.5 and 2.875. Fireballs and TNT hurt it as in vanilla (our play). The user
  (2026-09-28) sees ours bite a player several times in one charge and believes Hypixel's bites once; by their own
  word that is a guess for a capture to settle. The logs so far hold the dragons' paths but no player movement,
  so a charge cannot be matched to the hurts it caused; `capture-bedwars.js` now logs players' moves while a
  dragon lives and the self position on every hurt, and the next Sudden Death through it is the test. Its
  flight fits vanilla's (random points within about 60 of the middle at y 70 to 120, or a player); whether it
  goes only for enemies does not show from one player's capture. Its own growl is the vanilla living sound at
  volume 5. From Sudden Death the board counts `More Dragons in 5:00` (cap): the second dragons come 5 real
  minutes later, whatever the event speed. At 4x the board went from Sudden Death straight to `Game End in
  2:30` and showed `More Dragons in 2:30` only after the game had ended, so a 4x game never reaches them. The
  wave (cap, Sky Rise 2026-09-25, normal speed, at 45:00): per team the same `SUDDEN DEATH: +1 <Team> Dragon!`
  line and a new dragon 15 blocks over its spawn point, one `mob.enderdragon.growl` at the player, then dark
  purple bold `MORE DRAGONS HAVE SPAWNED!` in chat, all in one tick. No title.
- Game End at 50:00 (cap, 2026-09-25, twice at 4x event speed, one against one with both alive): the board
  counts `Game End in` down to 0:01, then with no warning or draw line the end summary names one winner and
  the other reads red bold `GAME OVER!` (0/100/0). The team with the most players still alive wins (our
  play); with the same count Hypixel's pick looks arbitrary: the host won both, once as Gray and once as Red,
  with no kills either side. Draws exist and award neither a win nor a loss (off 2025-09-02). The catalog
  carries bold yellow `DRAW!`, `It's a draw.`, a yellow `At this rate, a draw will happen in N minutes!`
  warning and a `Draw in M:SS` label; none appeared. Ours breaks a tie between the teams with the most alive
  on final kills, then beds broken, then total kills; still tied, it is a draw between them and the rest
  lose. Both captured ties would have been draws.

### 3.4 Winning and rewards

- The last team with living players wins.
- End summary (cap): the winners' line in the team color, then `1st Killer`, `2nd Killer`, `3rd Killer`
  with kill counts, then reward lines and a replay link. Titles `VICTORY!` / `DEFEAT!` hold 200 ticks in
  the duel capture. In BedWars (cap, 2026-09-25) the last final kill's line, the summary block, gold bold
  `VICTORY!` (0/100/0, empty subtitle) and `TEAM ELIMINATED >` arrive in that order within 11 ms; the replay
  line follows 5.2 s later, and a private game prints no rewards. The player whose final death ended the game
  read red bold `GAME OVER!` the same way (cap), and was sent to the lobby 5.9 s later.
- XP (off, since 2025-06-30): win +100 Solo/Doubles, +50 3s/4s, +25 4v4 and Dreams; +15 a minute played;
  +5 for the first kill on each player; +10 per final kill; +15 per bed; +2 per diamond and +3 per emerald
  picked up from a map generator. Captured lines: `+15 Bed Wars XP (Time Played)`, `(First Kill)`,
  `(Bed Break)`, `(Diamonds)`, and at the end `(Final Kill + Position Bonus)`.
- Tokens (cosmetic currency, stored as `coins`): an MVP+ player got +12 per time-played award, +24 per bed,
  +6 per final kill, +60 per win (cap), each echoed on the action bar. Multipliers by rank: CAPTURE.

## 4. Map anatomy

- **Team island**: bed, spawn, forge (iron and gold), item shop NPC, upgrades NPC, team chest on the left
  and ender chest on the right (every map since 2018).
- **Shopkeepers** (cap): both are blacksmith villagers with no name of their own. Armor stands above them
  read aqua `ITEM SHOP` over yellow bold `RIGHT CLICK`, and in Solo aqua `SOLO` and `UPGRADES` over `RIGHT
  CLICK`, lines 0.25 to 0.28 apart. Doubles prints `TEAM` in place of `SOLO` (cap).
- **Team chest**: shared. Other teams cannot open it until the owning team is fully eliminated, every
  member final-killed (our play; the 2017 claim of access once the bed breaks is stale). Until then it
  refuses with red `You cannot open this Chest as the <Team> has not been eliminated!`, the team in its
  color (cap).
- **Ender chest**: personal, the same contents from any ender chest on the map.
- **Punch to deposit**: punching a chest with resources in hand deposits the held stack; sneak-punch
  deposits every stack of that item; nearby players hear it (off 2024-12, 2025-01). Chat (cap): gray
  `Deposited `, `x13 Iron Ingot ` in the item's color (iron and wool white, gold gold, diamonds aqua,
  emeralds green), gray `into `, aqua `Team Chest` or light purple `Ender Chest`, gray `! `, dark gray
  `(131 Total)`: the chest's count of that item. The sound is `random.chestclosed` (volume 1, pitch 1.0) at
  the depositor's feet, not the chest (cap). A single bow is refused with red `You need at least 2 items of
  this type to deposit!` (cap); a single resource deposits. Tools (shears, axes, pickaxes) get red `You
  can't deposit this item!` (our play; cap for shears).
- **Deposits, exactly** (cap, every Bed Wars capture through 2026-09-27, `research/bedwars/deposits.py`
  rebuilding the inventory from its slot packets): any item deposits, not just resources. A lone cobblestone, bridge egg or Magic Milk went
  in; a sneak-punch took every stack of it, 66 gray wool held as 2, and 20 of 29 water buckets as the
  chest had room. Only swords and bows need two: a lone iron sword was refused, and so were two stone
  swords when one had Sharpness and the other not; two plain ones put one in. Punching another side's
  chest reads red `You cannot deposit into this chest as the ` + `Gray Team` in its color + red ` has not
  been eliminated!`; a full chest reads red `No space available to deposit items.` Names are the 1.8
  client's (`Oak Wood Planks`, `Silver Stained Clay`) or the item's own (`Blast-Proof Glass`).
- **Ours (2026-09-28)**: Bed Wars' `Chests` over Polyp's containers. The team chest is its base's (the
  `bedwars:base` region holding it) and opens to another side once that side no longer stands; a store
  punched or opened is filed under its side, so a write the kit's stamps read (Sharpened Swords) restamps
  the team chest and every member's ender chest, the way the held items are. A sword or bow needs two of
  its material, not two identical items, and only the held one goes in, sneaking or not. The wooden sword
  and the tools stay out of chests through the window too (Polyp's `ContainerInsertEvent`): Hypixel lets
  the wooden sword in and hands out another. Item names are each client's own, so a 1.8 client reads
  1.8's through Via. A final death spills the ender chest at the side's forge as a generator drops, and
  the line prints only when something fell.

| Mode | Bases | Diamond gens | Emerald gens |
|---|---|---|---|
| Solo, Doubles | 8 | 4 | 4 |
| 3s, 4s | 4 | 4 | 2 (3 on Catalyst, and by their blocks Obelisk and Unturned) |
| 4v4 | 2 | 2 | 2 |

- **Build limits** are per map: minimum Y, maximum Y and a build radius. A map file added 2026-09-24
  (`research/bedwars/bwmapinfo.json`, in Hypixel's own field names: `pool`, `minBuild`, `maxBuild`,
  `buildRadius`, `festival`, `reskinOf`) has them for 158 maps: 83 eight-team and 75 four-team, 46 of them
  festival versions, no 4v4 maps. Medians: eight-team radius 121.5, four-team 103, both built from y 62 or 63
  up to 96, about 32 blocks of height; Fandom's pages agree. 11 rows have radius -1, and Temple's has no limits
  at all (-1, 0, -1); the arenas leave out what a row lacks. The file has no protected areas around bases and
  generators. The radius is round, not square: Rooftop and Lasagne took blocks well past where a square's
  edge would be (the square was our play). Measured flat from 0,0 through block coordinates, a block goes in
  while its distance is under the radius, and Rooftop, Sky Rise and Ironclad stop at their file's 120, 123
  and 131 (cap, 2026-09-25 to 27). The area is that circle at every height, with one height limit across the
  whole map (our play). Elsewhere the edge falls between whole numbers, and each arena takes the simplest
  number between the last block placed and the first refused. No way of measuring makes every radius whole:
  the one that fits every map gives whole numbers on 5 of the 11 probed, and Temple's edge falls between
  100.18 and 100.25. On Shipment no one circle round 0,0 takes
  both its south edge (114.44 to 114.53) and its west (114.76 to 114.86), though one centered up to a block
  off 0,0 would; its arena keeps 0,0 and grows to take every block placed, 114.8 (decided 2026-09-27: expand
  where the probes disagree), which only the south refusal contradicts. The captures give
  the limits the file lacks (2026-09-27):

  | Map | Floor | Height, first refused | Radius |
  |---|---|---|---|
  | Lasagne | 61 | 91 | 121 |
  | Shipment | 67 | 102 | 114.8: the south edge 114.44 to 114.53, the west 114.76 to 114.86 |
  | Tengshe | 64 | 101 | 113.6: 113.53 to 113.60 |
  | Temple | 43 | 106 | 100.2: 100.18 to 100.25 |
  | Babylon | 55 | 108 | 135.6: 135.53 to 135.95 |
  | Silver Birch | 73 | 115 | 135.2: 135.16 to 135.35 |
  | Jurassic | the file's 64 | the file's 95 | 99.41: 99.41 to 99.46 |
  | Waterfall | the file's 58 | the file's 101 | 99: 98.68 to 99.37 |

  The file is wrong about one floor: Fort Doon's is 68 in play, not the file's 42 (refused at 66 and 67
  under the base, 2026-09-27). Every other captured limit agrees with the file.
- **Pools**: the file puts every map in `BEDWARS_8TEAMS_FAST`, `BEDWARS_8TEAMS_SLOW`, `BEDWARS_4TEAMS_FAST`
  or `BEDWARS_4TEAMS_SLOW` (50, 33, 37 and 38 maps). FAST and SLOW do not match the forge speeds the
  community lists: Airshow, Glacier, Orchestra, Speedway, Waterfall and Solace are slow iron in both
  NamuWiki's list and the 2020 map-admin list, yet all six are 8TEAMS_FAST, and Fandom calls every four-team
  map fast iron while 38 are 4TEAMS_SLOW. Either the pool means something else (SLOW eight-team maps are
  bigger, median radius 130.5 against 117.5, though the four-team pools barely differ) or the lists are stale
  since Hypixel standardized speeds in 2023. Settled (cap, Solo, 2026-09-25): the pool is not the forge
  speed. Glacier, in 8TEAMS_FAST, has the slow one-spawner forge the lists give it, and Bio-Hazard, in
  8TEAMS_SLOW, the fast three-spawner one; Amazon (SLOW) is slow. So the file gives no forge class, and the
  community lists were right about Glacier.
- **Above the top**: more than one block above the limit for over 30 s starts damage that grows; the timer
  decays and resets on death (off 2026-05).
- **No-place zones** are boxes; refusal line (cap) `You can't place blocks here!`, with no sound. Measured
  2026-09-26 (cap, Solo private games on 25 maps, the host's Disable Block Protection and Max Team Upgrades
  on; the zones held with that modifier; `research/bedwars/zones.py` draws them, `zones_fit.py` and
  `base_boxes.py` fit them):
  - Placing and breaking are refused alike inside a zone: red `You can't place blocks here!` and red `You
    can't break blocks here!`. With the modifier on map blocks break outside the zones, so a break can find
    an edge a placement cannot reach.
  - Every generator keeps a cube, the forge included: 3 blocks out each way from the generator's item block,
    down as well as up, 7 x 7 x 7. Blocks go in and break 4 out, 4 up and 4 down; breaks under a generator
    are refused to 3 down. Diamond and emerald generators on Ashfire, Amazon and Ambush agree, and so do the
    forges on all 25 maps (one stray placement 3 up on Lucky Rush).
  - Each base keeps a box centered on its spawn block, sized per map (below). The forge's cube sits 3 to 6
    blocks behind the spawn and sticks out of the box. A map's bases can be flipped copies of each other (our
    play; Playground's two probed bases have their item shops on opposite sides), and a centered box is the
    same either way.
  - Each shopkeeper keeps a column 1 block out around it (3 x 3), from 5 below its feet to 5 above; blocks go
    in 2 out and 6 up. Where a shopkeeper stands inside the base box only the part sticking out shows.
  - Earlier (Lucky Rush 2026-09-24): over the base, blocks were refused 5.8 and 7.2 blocks from the forge but
    went down from 6.6, so the zone is a box, not a radius. A reading of refusals out to 13 blocks and at y 76
    came from pop-up tower clicks, which the capture tool counted as placements until 2026-09-25.

  Base boxes: how far each reaches from the spawn block, the same both ways, facing away from the forge.
  Every axis the captures closed on both sides (76 of 81) is centered on the spawn block. The five sides
  marked were never closed and take the opposite side's reach; no success falls inside the result.

  | Map | Back and front | Left and right | Down and up | Size | Taken from the opposite side |
  |---|---|---|---|---|---|
  | Amazon | 6 | 8 | 4 | 13 x 17 x 9 | |
  | Ambush | 5 | 6 | 5 | 11 x 13 x 11 | |
  | Ashfire | 5 | 5 | 5 | 11 x 11 x 11 | |
  | Babylon | 6 | 6 | 6 | 13 x 13 x 13 | |
  | Bio-Hazard | 6 | 6 | 4 | 13 x 13 x 9 | bottom, where the floor is 5 down |
  | Crogorm | 5 | 4 | 4 | 11 x 9 x 9 | |
  | Deadwood | 5 | 5 | 3 | 11 x 11 x 7 | |
  | Dockyard | 9 | 4 | 5 | 19 x 9 x 11 | |
  | Dragonstar | 5 | 5 | 6 | 11 x 11 x 13 | bottom: refused 5 down, the floor 7 down |
  | Gateway | 7 | 5 | 6 | 15 x 11 x 13 | |
  | Glacier | 8 | 6 | 4 | 17 x 13 x 9 | back: refused 6 back, allowed 9 back |
  | Harvest | 8 | 7 | 4 | 17 x 15 x 9 | |
  | Impere | 6 | 6 | 4 | 13 x 13 x 9 | |
  | Ironclad | 5 | 9 | 6 | 11 x 19 x 13 | |
  | Lightstone | 5 | 6 | 14 | 11 x 13 x 29 | |
  | Lucky Rush | 6 | 7 | 7 | 13 x 15 x 15 | |
  | Mirage | 8 | 8 | 6 | 17 x 17 x 13 | |
  | Orchestra | 5 | 9 | 4 | 11 x 19 x 9 | on Orchestra v2, the live build (2026-09-27) |
  | Playground | 7 | 7 | 11 | 15 x 15 x 23 | bottom: refused 6 down |
  | Rooftop | 7 | 6 | 4 | 15 x 13 x 9 | |
  | Rooted | 7 | 9 | 4 | 15 x 19 x 9 | |
  | Sanctum | 6 | 6 | 7 | 13 x 13 x 15 | bottom: refused 6 down, the floor 8 down |
  | Scorched Sands | 7 | 8 | 6 | 15 x 17 x 13 | |
  | Serenity | 7 | 6 | 5 | 15 x 13 x 11 | |
  | Silver Birch | 8 | 6 | 8 | 17 x 13 x 17 | |
  | Sky Rise | 7 | 7 | 5 | 15 x 15 x 11 | |
  | Solace | 5 | 4 | 5 | 11 x 9 x 11 | |
  | Waterfall | 5 | 5 | 5 | 11 x 11 x 11 | |

  Each box merges every base probed on its map: two bases on Ambush, Bio-Hazard, Deadwood, Playground,
  Sanctum and Silver Birch, the same base twice on five more. Every refusal and success fits its box but 3
  cells on Impere and 1 each on Babylon and Crogorm.

  Four-team base boxes (cap, 4s private games on all 28 maps in rotation with Disable Block Protection and Max
  Team Upgrades, 2026-09-27, through the capture tool's zone check) follow the same rule: every axis closed on
  both sides is centered on the spawn block but one. Many four-team spawn points sit on a block's corner
  (Lasagne's at 0.0, 65, -95.0), and their boxes are still centered on the block under it. On eight maps the
  region runs down to the build floor or past it (our play: the floor floors it), so nothing below it goes in
  and its bottom never closes; a placement there draws both `You can't place blocks here!` and `You cannot
  build any further down!`. The arenas take the top's reach for a bottom never closed.
- **Refusals, ours (2026-09-28)**: the refusing rule names why (`Building`: reserved zone, floor, height, off
  the circle, a map block), and Bed Wars' `Lines.refusal` picks the captured copy by it: `You can't place blocks
  here!` and `You can't break blocks here!` for a zone, `You cannot build any further down!` under the floor,
  `You can only break blocks placed by a player!` for a map block, and over the height `Build height limit
  reached!`, the string catalog's wording (CAPTURE). The user's rule: in Bed Wars a refused placement or break
  sounds to nobody but the actor's own client, where bridge lets everyone hear (`Rulesets.BASE`:
  `fx/polyp:block_place_refused = to(predicted-for(source), place-sound)`, a 1.8 placer getting the sound
  its client cannot predict, and `block_break_refused = none`).
- **Breaking and drops** (Hypixel keeps vanilla's, the user says, but for wood): a block drops nothing
  without the tool it needs (1.8 `Material.rock`, `iron`, `anvil`, `snow`, `craftedSnow` and `barrier`
  want one; `ItemPickaxe.canHarvestBlock` wants diamond for obsidian, iron for gold, diamond, emerald and
  redstone, stone for iron and lapis), so hardened clay, end stone and obsidian need a pickaxe and glass,
  stained glass, ice and packed ice drop nothing at all (1.8 `quantityDropped` 0), while wool, wood, ladders
  and sponges drop by hand. Ours (2026-09-28): Polyp reads the block tags for the gate, which say the same of
  every block 1.8 had, and 26.1's loot tables for what falls (`BlockLoot`), with 1.8's own drops first under
  the 1.8 presets (`LegacyLoot`: lapis 4 to 8, the ore itself for iron and gold, no sticks off leaves, a
  bare-handed dead bush nothing, two-tall grass no seeds, snow layers plus one). Bed Wars' blocks all agree
  between the eras.

  | Map | Back and front | Left and right | Down and up | Size | |
  |---|---|---|---|---|---|
  | Aquarium | 6 | 5 | 5 | 13 x 11 x 11 | |
  | Archway | 4 | 6 | 5 | 9 x 13 x 11 | bottom: refused 3 down, never closed |
  | Artemis | 5 | 4 | 5 | 11 x 9 x 11 | |
  | Build Site | 9 | 9 | 9 | 19 x 19 x 19 | bottom: refused past the floor, 5 down |
  | Carapace | 6 | 7 | 6 | 13 x 15 x 13 | bottom: refused to the floor, 6 down |
  | Catalyst | 7 | 6 | 5 | 15 x 13 x 11 | |
  | Fort Doon | 5 | 6 | 4 | 11 x 13 x 9 | |
  | Frogiton | 8 | 6 | 5 | 17 x 13 x 11 | bottom: refused to the floor, 5 down |
  | Graveship | 7 | 9 | 5 | 15 x 19 x 11 | |
  | Harvesting | 9 | 10 | 4 | 19 x 21 x 9 | |
  | Holmgang | 7 | 6 | 8 | 15 x 13 x 17 | |
  | Horizon | 6 | 6 | 4 | 13 x 13 x 9 | |
  | Infinite | 5 | 7 | 4 | 11 x 15 x 9 | |
  | Invasion | 5 | 5 | 5 | 11 x 11 x 11 | |
  | Jurassic | 6 | 6 | 6 | 13 x 13 x 13 | bottom: refused past the floor, 4 down |
  | Katsu | 7 | 8 | 8 | 15 x 17 x 17 | |
  | Kubo | 8 | 7 | 13 | 17 x 15 x 27 | bottom: refused to the floor, 7 down |
  | Lasagne | 8 | 11 | 6 | 17 x 23 x 13 | bottom: refused past the floor, 4 down |
  | Lectus | 5 | 5 | 13 | 11 x 11 x 27 | bottom: refused past the floor, 11 down |
  | Lost Temple | 8 | 8 | 11 | 17 x 17 x 23 | left and right closed at 8 and 7; bottom: refused past the floor, 4 down |
  | Paladin | 8 | 8 | 9 | 17 x 17 x 19 | |
  | Relic | 6 | 5 | 5 | 13 x 11 x 11 | |
  | Shipment | 8 | 7 | 4 | 17 x 15 x 9 | |
  | Swashbuckle | 5 | 5 | 5 | 11 x 11 x 11 | |
  | Temple | 6 | 6 | 6 | 13 x 13 x 13 | |
  | Tengshe | 5 | 6 | 4 | 11 x 13 x 9 | not in the archive |
  | Terminal | 5 | 6 | 7 | 11 x 13 x 15 | bottom: closed by breaks past the floor, 5 down |
  | Whiskers | 8 | 9 | 5 | 17 x 19 x 11 | |

  Lost Temple's lopsided axis takes 8 both ways in its arena (the standard, 2026-09-27). Every refusal and
  success fits its box but 3 cells on Carapace and 1 on Temple.

  The edge itself (cap, Sky Rise 2026-09-25, map-file radius 123): blocks went down out to 122.7 from 0,0 and
  the next, 123.7 out, was refused with `Build limit reached!`, a line of its own. Height (cap): Sky Rise took
  wool at y 90 and refused 91 (map file 91), a water bucket included; Waterfall placed at y 100 and refused
  101, its map file's number, with red `Build height limit reached!` and no sound. The file's maxBuild is the
  first height refused. The floor (cap, Ashfire 2026-09-26, map file 66): blocks went in at y 66 and were
  refused at 65 with `You cannot build any further down!`.
- **Breaking**: only player-placed blocks. Refusal line (cap): `You can only break blocks placed by a
  player!`. Inside a generator's area it is red `You can't break blocks here!` (cap, Waterfall's emerald
  platform); neither plays a sound. Plants outside protected areas break without drops (off 2022).
- **Void**: below the minimum build limit a player cannot drop items or open the inventory (wiki), which
  stops resource denial. Below y 0 a player dies at the next once-a-second check, with no damage on the way
  down (cap, twelve falls). On Waterfall all five of ours died on the same tick of a 20-tick beat, 0.08 to
  0.88 s after passing y 0: a slow sink in a falling water column at y -0.96, fast falls as deep as -48.
- **Liquids**: the water bucket is single use and its source can only be blocked (off v1.4). Water flows as
  in vanilla (cap, Waterfall: a step every 5 ticks, over the island's edge and straight down past the
  floor), except that a flow update into a team's base region is cancelled (our play). On Babylon (cap,
  2026-09-25) water placed just outside the protected box over a team's spawn flooded the base floor and ran
  around the bed, but never into the box. The zone a bucket is refused in is the no-build zone (our play),
  but a bucket is judged by the block clicked, where a block is judged by the cell it fills. Clicking a block
  inside the zone gets red `You can't empty buckets here!`, air back and the bucket kept, even when the water
  would land outside it (cap): 13 of Babylon's 18 refusals on 2026-09-25 aimed above its box from the top
  layer, and 9 at Playground's front face on 2026-09-26 aimed out of it, where wool went into the same cells.
  Clicked outside, a bucket goes in even when its water lands inside the zone (our play). A placed bucket
  leaves nothing behind: the slot empties (cap, 87 uses). Ours (2026-09-28): a flow into a reserved cell (the
  base boxes) is cancelled under `space.flow.reserved` (off by default), so water stands still at the edge; the
  bucket is judged where its water lands, not where it was clicked, which is the rule the check was after; and
  the spent bucket goes with the water, `fluids/bucketKept` off (2026-09-28). Water placed in a game never
  flowed on the server (the user, 2026-09-28): a game's shard schedules its fluid ticks under itself, and only
  a shard on its own clock was ever ticked; Bed Wars runs on the server clock. Polyp's instance pass now
  carries every world riding the instance that has no clock of its own (SpongeTest drives it through
  `InstanceTickEvent`).
- **Blasts** (the user, 2026-09-28): a fireball or TNT takes blocks players placed, never the map's, and a
  protected box (a base, a generator, a shopkeeper) is its own matter. Ours: `space.blast` names what a blast
  takes (`placed`; add `map` for the map's blocks), `space.blast.reserved` lets one into a reserved box (off);
  a mode that names `space.blast` keeps its preset's blast rays (the fold strips them from every other), and
  a blasted block drops nothing (our play).

## 5. Economy

Four resources: iron and gold from the island forge, diamonds and emeralds from map generators (emeralds
also from a forge with the Emerald Forge upgrade). Resources are ordinary item entities, one per spawn,
never merged (comm, a 2026 client mod). The 2026-09-24 capture agrees: 218 spawns from forges and a
diamond generator, each a single item appearing at one fixed point per generator and moving the way vanilla
drops a block's item, 0.2 up and at most 0.1 each way across.

### 5.1 Island forge

- **Speed is per map**, in three classes. Hypixel's map admin (2020): slow, medium and fast are about 0.33,
  0.66 and 1.0 iron a second in Solo/Doubles, about 0.33/s more in team modes; the rate is doubled for the
  first 30 s; speed comes from 1 to 3 spawn nodes per team. A player measured about twice those rates,
  possibly inside the boost window. Most maps are fast today (Fandom: every 4-team map, 79 of 103
  Solo/Doubles maps).
- **Gold rate**: no source. Claimed ratios run from 4 to 16 iron per gold. Measured below: 16 to 1 at base.
- **Caps**: 48 iron and 16 gold near the forge (wiki, several sources); the very first fill reaches 64
  iron (wiki). Forge upgrades raise the caps since 2023-05 (off). The cap counts resources lying near the
  generator; items thrown in by players do not. Measured below.
- **Forge upgrades**: +50%, +100%, then emeralds, then +200% of iron and gold (the lore). Measured below:
  less than that, and gold stops at Golden Forge.
- An eliminated team's forge keeps producing at its level.

**Measured** (cap, 2026-09-24 and 25: Solo on Lucky Rush, Deadwood and Sky Rise, all in the 8TEAMS_FAST pool,
with identical numbers; every forge level bought one at a time and left to fill, on Lucky Rush and Sky Rise):

| Forge level | Iron period | Iron/s | Gold period | Gold/s | Iron cap | Gold cap |
|---|---|---|---|---|---|---|
| none | 51 ticks | 1.18 | 276 ticks | 0.072 | 48 (64 on the first fill) | 16 |
| first 30 s | 21 | 2.86 | 126 | 0.159 | | |
| Iron Forge | 36 | 1.67 | 201 | 0.10 | 56 | 18 |
| Golden Forge | 21 | 2.86 | 126 | 0.159 | 64 | 20 |
| Emerald Forge | 21 | 2.86 | 126 | 0.159 | 72 | 22 |
| Molten Forge | 21 | 2.86 | 126 | 0.159 | 80 | 24 |

- The forge is three iron spawners and one gold spawner on one point, each dropping one item per period.
  With no upgrade the three iron spawners fire together, 3 iron at once; once a level changes their period
  they drift apart and items come one at a time (at Iron Forge, 3 per 36 ticks spaced 9 apart).
- Iron per second is 3 per period, so Iron Forge gives iron +42% and Golden Forge +143%, not +50 and +100;
  gold gains +37% and +119%. From Golden Forge on, iron and gold run at the start boost's speed, and neither
  Emerald nor Molten Forge speeds them further: Molten brings faster emeralds and the bigger cap only. Golden
  and Emerald read 21 on Lucky Rush and Sky Rise alike (an earlier 27 was a rate over 8 items; grouped by
  cycle, the same spawns give 21).
- The start boost lasts 600 ticks. With the forge kept empty through it (Sky Rise, 2026-09-25) iron came 3
  every 21 ticks until +578, then at +598 both iron and gold spawned on the spot and switched to 3 every 51
  and 1 every 276; gold ran every 126 before. The first iron and gold appear 4 to 12 ticks after `Protect
  your bed`. A forge nobody empties fills to 64 iron during the boost and stops; after it the cap is 48.
  Sky Rise matched Lucky Rush and Deadwood: all three are in the 8TEAMS_FAST pool.
  Doubles on Sky Rise ran the same forge as Solo (2026-09-25).
- Caps grow 8 iron and 2 gold per level. A capped forge restarts as soon as an item leaves: a pickup, or the
  5-minute despawn.
- **Drops, exactly** (cap, Sky Rise Solo 2026-09-25, #1640 and its neighbors): the spawn packet carries vanilla's
  item hop (0.2 up, up to 0.1 sideways) and the very next tick a velocity of `0, -0.0391, 0`: the server zeroed
  it, so the item falls straight and lands on the point (a teleport to `65.50` under a spawn at `66.00`, the
  pad half a block under). Every drop is its own entity of one, never merged: a forge holds 48 single iron, and
  a walk-over destroys them all at once. Ours (2026-09-28): `Generators.drop` spawns with the hop and zeroes the
  velocity, unmergeable; the cap counts what lies within 3 blocks, one to an entity. The drops used to scatter
  off the slab pad, because Polyp shoved any item whose cell was a solid block where 1.8's `pushOutOfBlocks`
  reads `isBlockFullCube` and a slab is no cube (GeneratorsTest ticks the items by hand). Each spawn on the
  wire carries a random yaw (vanilla's `EntityItem`), ours too since 2026-09-28. The user still sees Hypixel's
  pile as rotated and spread where ours sits identical; a 1.8 client spins and bobs each item off a random
  phase of its own (`RenderEntityItem`, `hoverStart`), so the difference is not in the positions we have. A
  capture of a forge filling through our own server is owed to diff the wires.
- Emerald Forge adds an emerald spawner: 1300 ticks (65 s) at Emerald Forge, 701 (35 s) at Molten, as
  NamuWiki says. The cap is 3 (cap, Waterfall 2026-09-25: a Molten forge from the Max Team Upgrades
  modifier stopped with 3 emeralds lying, its iron and gold at the usual Molten 80 and 24), and 2 at Emerald
  Forge (cap, 2026-09-26: Orchestra, Sky Rise and Amazon, every level bought in turn; each level's iron and
  gold caps matched the table on all three, slow forges and fast). A forge that fills as an old item
  despawns spawns in the same tick, and the spawn can reach the client before the item leaves, so a count
  taken at the spawn reads one high (Sky Rise's 73 against 72).
- A purchase restarts the forge at once: iron and gold spawned within a second of every forge line unless
  the forge was full, and an emerald came 0.06 to 1.8 s after both the Emerald Forge and the Molten Forge
  line, in three games. In a fourth (Bio-Hazard, 2026-09-25) none came at the Emerald Forge line, the first
  1301 ticks after it, with the forge holding 64 iron (the Golden cap) when bought; Molten's came at +23t.
- **Slow forges** (cap, Solo, 2026-09-25: Amazon twice and Glacier): one iron spawner, not three. It dropped
  1 iron every 21 ticks through the boost and 1 every 51 after it, a third of a fast forge; gold ran the same
  (126, then 276). The pool does not tell a slow forge (above). The start boost runs every spawner at 21
  ticks, Max Team Upgrades or not, so any game's first 15 s of iron counts its forge's spawners
  (`research/bedwars/forge_class.py`, every game on a map agreeing, 2026-09-24 to 26). One spawner:
  Amazon, Ashfire, Dragonstar, Glacier, Rooftop (as NamuWiki says), Solace and Waterfall. Three: Ambush,
  Babylon, Bio-Hazard, Crogorm, Deadwood, Dockyard, Gateway, Harvest, Impere, Ironclad, Lightstone, Lucky
  Rush, Mirage, Playground, Rooted, Sanctum, Scorched Sands, Serenity, Silver Birch and Sky Rise. Doubles
  matched Solo on the six maps played in both. Orchestra has one too. Two slow forges were bought through
  Molten Forge (cap, Solo, 2026-09-26, the 1 s respawn only: Orchestra twice, Amazon once), and both ran a
  fast forge's periods on one spawner at every level (iron 51, 36, then 21 from Golden Forge on; gold 276,
  201, then 126; emeralds 1302 at Emerald Forge and 701 at Molten) with a fast forge's caps (48, 56, 64, 72
  and 80 iron; 16, 18, 20, 22 and 24 gold; 2 and 3 emeralds). An earlier Orchestra record's stop at 23 iron
  is the connection, which went quiet 21 s before it dropped, not a cap.
- **Every Solo/Doubles map in rotation** (cap, 2026-09-26: a private game on each of the 27 with only the
  1 s respawn, Crogorm in Doubles): each forge's spawner count matched the list above, its boost and
  no-upgrade timing matched the logged forge with as many spawners, and with no upgrade it stopped at 48
  iron and 16 gold; every fast forge's boost filled to 64 first. The upgrades were bought on Gateway,
  Ironclad, Sky Rise, Orchestra and Amazon; the other maps take their levels from those. One Dragonstar game
  read 46 iron because its forge was picked up from early (our play); the rerun read 48.
- **Four-team forges** (cap, 2026-09-25: 3s on Lasagne and 4s on Relic, private games whose only modifier was
  the 1 s respawn, every level bought in turn): slower spawners than Solo's, with Solo's caps.

| Forge level | Iron period | Gold period | Iron cap | Gold cap |
|---|---|---|---|---|
| none | 61 ticks | 151 ticks | 48 (64 on the first fill) | 16 |
| first 30 s | 31 | 76 | | |
| Iron Forge | 45 | 113 | 56 | 18 |
| Golden, Emerald and Molten Forge | 31 | 76 | 64, 72, 80 | 20, 22, 24 |

  Relic's forge drops 5 iron a wave and Lasagne's 3, so Relic runs 1.64 iron a second with no upgrade and
  3.2 from Golden Forge on, Lasagne 0.98 and 1.94. Holmgang (4s, 2026-09-25) runs like Relic: 5 a wave,
  61 and 31 ticks, gold 151 and 76. Relic and Holmgang are in 4TEAMS_SLOW and Lasagne is not in the map
  file: the pool does not tell the speed here either. The forge's emeralds come faster than Solo's: 1101
  ticks (55 s, three spawns) at Emerald Forge and 541 (27 s) at Molten. On Aquarium (3s, 2026-09-27) the
  Emerald Forge's emeralds kept coming every 1101 ticks until the oldest despawned at 5 minutes, 6 lying at
  most, so any cap is above 6; a Solo Emerald Forge stops at 2. Molten Forge stops at 7 (cap, Aquarium 4s
  under Max Team Upgrades, 2026-09-27): emeralds every 541 ticks, and a forge left alone held 7 through two
  drops that never came; another held 7 when emptied (our play: 7 from a maxed team forge).
- **Every 3s map in rotation** (cap, 2026-09-27: a private game on each of 29 with only the 1 s respawn,
  Aquarium bought through Molten Forge): 3 spawners on every map, Relic and Holmgang included, on the
  four-team timing above, stopping at 48 iron and 16 gold with no upgrade. The boost ends before a 3s forge
  fills: about 60 iron by 0:30. A 4s forge is the same map's 3s forge with 5 spawners: the same timing and
  caps on all ten maps played in both (Aquarium, Archway, Artemis, Fort Doon, Holmgang, Invasion, Jurassic,
  Relic, Temple and Terminal, 2026-09-25 and 27), so the other 4s maps are taken to run that way (our play).
  Jurassic's and Temple's forges drop iron from three points a block apart and gold at the middle one (half
  a block under it on Temple), each wave landing over about 2 ticks. In 3s each point has one spawner; in 4s
  the two extra spawners drop at the gold point.

### 5.2 Diamond and emerald generators

| | Tier I | Tier II | Tier III | Cap S/D | Cap 3s/4s |
|---|---|---|---|---|---|
| Diamond (all modes) | 30 s | 23 s | 11.2 s | 4 | 8 |
| Emerald, Solo/Doubles | 65 s | 50 s | 35 s | 2 | |
| Emerald, 3s/4s | 55 s | 40 s | 27.4 s | | 6 |

- Intervals are NamuWiki's; 11.2 s and 27.4 s are whole ticks (224, 548). 2026 client mods use 30/23/12
  and 65/50/35 (8 teams) or 55/40/27 (4 teams).
- **Measured** (cap, Solo, 2026-09-24): diamonds every 601, 451 and 226 ticks at tiers I, II and III, that
  is 30, 22.5 and 11.25 s plus one tick each; Solo emeralds every 1001 ticks at tier II (50 s plus one). The
  first diamond came 8 to 24 ticks after the start, with no wait. Tier II and III lines at 6:00 and 18:00
  (diamond) and 12:00 and 24:00 (emerald). While a generator is capped its countdown keeps restarting with
  no spawn: 4 diamonds and 2 emeralds, the Solo caps. Solo emeralds at tier I every 1301 ticks (65 s plus
  one, Rooftop) and at tier III every 701 (35 s plus one, Waterfall, 31 spawns). Every Solo/Doubles
  generator stops at 4 diamonds or 2 emeralds, on every map (our play).
- NamuWiki's timing is self-consistent with the capture: the first Solo emerald lands at 0:31, and at an
  emerald tier-up the pending timer resets and an emerald spawns at once. Diamonds do the same (cap, Sky
  Rise 2026-09-25): the first diamond 8 ticks after the start, then every 601, and one on the spot with the
  Tier II line.
- **First mid emeralds** (cap, Solo on Rooftop and Amazon, 2026-09-25): all four emerald generators spawned
  their first emerald 606 to 617 ticks after the start (0:30.3 to 0:30.9), then every 1301; Amazon's came at
  +609. The Emerald II line brought one on the spot. Before that first emerald the countdown already counts to
  the second one: at 0:26 it read 70 s, due at +1910, and the second came at +1911. First diamonds came 8 to
  27 ticks after the start on four maps. Max Team Upgrades leaves the mid generators alone: their tiers ran
  at the usual 601, 451, 1301 and 1001 ticks under it.
- Four-team mid generators (cap, Relic 4s 2026-09-25): diamonds every 601, 451 and 226 ticks as in Solo,
  emeralds every 801 at tier II (40 s plus one), with the tier lines at the Solo times. Emeralds every 1101
  at tier I (Terminal and Aquarium 4s, 2026-09-27). Diamonds stop at 8 (cap, Aquarium 4s twice and
  Terminal, 2026-09-27): eight drops 601 ticks apart, then none until the oldest despawned. Mid emeralds
  vanish 300.4 to 300.8 s after they drop, so at tier I no more than 6 lie there and a four-team emerald
  cap can only show from tier II.
- **Four-team caps on every map in rotation** (cap, 4s on all 28, 2026-09-27): diamonds stop at 8 and
  emeralds at 6 at every generator read, at tier II or III. Catalyst's three emerald generators stop at 6
  each too, read at tier II in two games and at tier III in the second. Four-team diamonds always stop at 8
  (our play).
- Item spawns reached the client from 85 blocks away (Sky Rise), so a generator across the island is still
  measured.
- **Holograms** (armor-stand name lines over a spinning block on an armor stand): yellow `Tier` + red
  numeral; bold aqua `Diamond` or bold dark green `Emerald`; yellow `Spawns in ` + red number + yellow
  ` seconds` (singular form exists). On an Amazon diamond generator (cap, 2026-09-25) the lines stand 0.375
  apart above the block stand's highest point: the countdown at 2.0, the name at 2.38, the tier at 2.75. The
  block turns one and a half times one way and back, 60 ticks each way, fastest mid-swing at 14° a tick,
  rising 0.43 blocks on one sweep and sinking on the other, with a move packet every tick. The countdown
  renames once a second. An emerald generator's display is the same (cap, Amazon 2026-09-25): the same line
  heights, swing and rise.
- **Hologram stands, exactly** (cap, 2026-09-27, `research/bedwars/stands.py`): every line is an invisible armor
  stand with its name shown. A generator's are marker stands (sized 0, so the text is half a block over the
  stand): the countdown, name and tier stands 2.59375, 2.96875 and 3.34375 over the drop point. The block is an
  invisible marker stand wearing it, 0.59375 over the drop point at the top of its bob, moved every tick; the
  turn speeds up and slows down (0.7 a tick, capped near 14.1), resting a tick or two at each end, and the
  sink is in step with it. A keeper's are full-size stands (their text 2.475 over them) at 0.4375, 0.15625
  and -0.09375 over the villager: `SOLO` or `TEAM`, `UPGRADES` or `ITEM SHOP`, `RIGHT CLICK`.
- **Ours (2026-09-28)**: Canvas `Hologram`, a line a marker stand, the text at its captured height for every
  client (1.8 sizes a marker stand 0 x 0 as today's do). The keepers' lines are marker stands too, placed so
  their text lands where Hypixel's full-size ones draw it: a full-size stand's hitbox stands in the villager and
  can take the click meant for it. The block turns out and back on a cosine, 540 degrees over 60 ticks each way
  with the captured 14.1 a tick at mid-sweep, every generator in step as captured, sinking 0.43 with the turn.
  The countdown reads the generator's own clock every tick and sends only a change.
- Items near a generator count toward its cap until carried away.

### 5.3 Pickup

- **Forge splitting**: when a player picks up forge iron or gold, every teammate of theirs close by gets a
  full copy, not a share. It works at any team's forge, and only ever within a team: a Red and a Blue player
  at the same forge never split (our play). Iron and gold only: diamonds and emeralds never split, whether a
  forge or a diamond or emerald generator made them (our play). Treated as intended. Measured (cap, Doubles
  on Sky Rise 2026-09-25): you got a copy standing within about 2.4 blocks of the item your teammate picked
  up (55 times) and never from 2.5 blocks on (106 times), so a radius of about 2.5, counted from the item
  (our play). The carry limit below stops split copies too; standing still does not (our play).
- **Carry limit** (our play): past a threshold you stop picking up generated iron or gold, the items the
  forge made that nobody has picked up yet, the same items its cap counts. It covers iron and gold only, and
  never items anyone dropped. Captured 2026-09-24 (Solo, Lucky Rush, every forge level bought): the last
  pickup takes you to 513 iron or 65 gold (one still goes in at 512 and at 64), then nothing more while the
  forge keeps spawning beside you. Nothing is said: the `You didn't pick up any more Iron because you have
  too much on you!` line in the string catalog never came.
- **Despawn**: generated items that nobody picks up vanish 300 s after they spawn (vanilla's 6000-tick item
  age), and they never merged in that capture.
- **Standing still** (our play): after 30 s without moving you stop picking up items until you move: every
  item on the ground, generated or dropped. Split copies still reach you. Neither carry limit above is this
  rule: gold kept going in for 108 s after iron stopped, and at 65 gold two emeralds lying on the same block
  as two gold ingots went in while the gold did not (cap, Lucky Rush).
- **Provenance**: generator XP and several achievements need each item tagged with where it came from.

### 5.4 What a generator capture has to separate

Generators need a campaign of their own, not one session, because their numbers move along several axes
at once:

- **Mode and map.** Forge speed is set per map and can differ between modes on the same map (the map admin
  in 2020: some maps are slow in Solo and medium in Doubles); four-team forges gained an extra item per
  spawn in 2017. Emerald generator counts (2 or 3 in 3s/4s) and generator caps vary by map.
- **Time.** The forge runs at Golden Forge speed for the first 30 s (measured, 5.1). Diamond and emerald
  generators change interval at each tier (6, 12, 18 and 24 minutes). Each generator's first spawn after the
  start is its own number: the first Solo emerald lands at 0:31, not after a full 65 s.
- **Upgrades.** Each forge level changes both the speed and the cap (2023-05); Emerald Forge adds emeralds.
- **What lies there.** A cap counts generated items not yet picked up, near the generator. Items a player
  threw there do not count (the 2022 fix), so a capture has to tell generated items from dropped ones.
- **What you hold.** Past a threshold you stop picking up generated iron and gold, the same not yet picked up
  items the cap counts; dropped items always pick up, and diamonds and emeralds have no limit (our play).
  The thresholds are 512 iron and 64 gold (section 5.3).

We will use some of Hypixel's maps as they are, so each map's own numbers matter, not only the rules. Every
game played through the capture tool adds that map's record (section 13.1).

## 6. Item shop

Prices are Solo/Doubles, with 3s/4s in parentheses where it differs. 4v4 uses the 3s/4s prices and sells no
obsidian. The Solo/Doubles column matches the live shop item for item, price and quantity (a Solo game,
2026-09-24; every window is in `research/bedwars/shop-windows-2026-09-24.txt`). The 3s/4s prices match the
live shop too: a 4s game on Holmgang (2026-09-25) showed every tab, with the diamond sword at 3 emeralds, TNT
at 8 gold, the water bucket at 3 and 4 sponges for 3 gold, and everything else at the Solo price.

| Tab | Item | Qty | Price |
|---|---|---|---|
| Blocks | Wool (team color) | 16 | 4 iron |
| | Hardened Clay (team color) | 16 | 12 iron |
| | Blast-Proof Glass | 4 | 12 iron |
| | End Stone | 12 | 24 iron |
| | Ladder | 8 | 4 iron |
| | Wood (oak planks) | 16 | 4 gold |
| | Obsidian | 4 | 4 emerald |
| | Packed Ice (permanent since 2025-09) | 8 | 8 iron |
| Melee | Stone Sword | 1 | 10 iron |
| | Iron Sword | 1 | 7 gold |
| | Diamond Sword | 1 | 4 emerald (3) |
| | Stick (Knockback I) | 1 | 5 gold |
| Armor | Permanent Chainmail Armor | legs, boots | 24 iron |
| | Permanent Iron Armor | legs, boots | 12 gold |
| | Permanent Diamond Armor | legs, boots | 6 emerald |
| Tools | Permanent Shears | 1 | 20 iron |
| | Pickaxe: wood Eff I, iron Eff II, gold Eff III + Sharp II, diamond Eff III | tier | 10 iron, 10 iron, 3 gold, 6 gold |
| | Axe: wood Eff I, stone Eff I, iron Eff II, diamond Eff III | tier | 10 iron, 10 iron, 3 gold, 6 gold |
| Ranged | Arrow | 6 | 2 gold |
| | Bow | 1 | 12 gold |
| | Bow (Power I) | 1 | 20 gold |
| | Bow (Power I, Punch I) | 1 | 6 emerald |
| Potions | Speed II (45 s) | 1 | 1 emerald |
| | Jump V (45 s) | 1 | 1 emerald |
| | Invisibility (30 s) | 1 | 2 emerald |
| Utility | Golden Apple | 1 | 3 gold |
| | Bedbug | 1 | 24 iron |
| | Dream Defender | 1 | 120 iron |
| | Fireball | 1 | 40 iron |
| | TNT | 1 | 4 gold (8) |
| | Ender Pearl | 1 | 4 emerald |
| | Water Bucket | 1 | 2 gold (3) |
| | Bridge Egg | 1 | 1 emerald |
| | Magic Milk | 1 | 4 gold |
| | Sponge | 4 | 2 gold (3) |
| | Compact Pop-up Tower | 1 | 24 iron |
| Quick Buy | Tracker, per enemy team | | 2 emerald, unlocks once every enemy bed is gone |

Price history is dense (chainmail, bedbug, water and sponge all changed on 2023-05-03; ladders, arrows,
the Power bow and the bridge egg on 2021-10-14). The Fandom GUI template still shows pre-2023 prices; the
table above is the newest documented.

### 6.1 What persists

| Item | On a normal death |
|---|---|
| Armor bought as Permanent (legs and boots; helmet and chest stay team leather) | kept |
| Shears | kept |
| Pickaxe, axe | drop one tier; tier I is permanent |
| Swords | lost; the wooden sword comes back whenever no better sword is held (our play: there is always at least the wooden one) |
| Bows, arrows, blocks, potions, utilities | lost, not dropped |
| Compass | given back; a bought tracker is lost. One team at a time (the user, 2026-09-28): buying the team already tracked is refused, our line `You are already tracking that team!` (Hypixel's uncaptured); tracking several, or a cheaper swap between teams, is an idea for later, not now |
| Iron, gold, diamonds, emeralds | to the killer (section 8) |
| Ender chest, team chest | untouched (the ender chest is dumped at a final death) |

- Tools are bought one tier at a time and the shop slot shows the next tier (`Tier: I` in the lore).
- A lower armor tier than yours is refused with red `You already have a higher tier item.` and no sound; the
  tier you own with red `You've already purchased this item!` and `mob.endermen.portal` (cap, 2026-09-25).
- Since 2023, a bought sword replaces the sword in your selected hotbar slot if it is better, and a better
  sword picked up replaces your current one. A worse sword than yours can still be bought (our play).
- Swords, exactly (cap, 4v4v4v4 on Invasion and Jurassic 2026-09-27, `research/bedwars/swords.py`): a bought
  sword takes the wooden sword's slot when the wooden one is held, whatever the held slot, and the wooden one is
  gone. With no wooden sword it goes in like any item, the first empty slot: a second stone sword went to slot 3,
  then 4; a stone bought over an iron one to slot 5. Nothing moved out of the hotbar. A sword picked up while the
  wooden one is held takes its slot too, its item removed with no pickup animation; any other sword picks up
  normally. The wooden sword comes back 0.1 to 0.45 s after the last other sword leaves the inventory. Sharpness
  stays with each sword: the purchase of Sharpened Swords put it on every sword the team had, held or stored (an
  ender chest's two stone swords and an iron one had it the next time it opened, and the user says the team chest
  does the same), and later bought ones come with it, but a sword picked up or put away afterwards kept its own,
  an unsharpened stone sword under the upgrade and a sharpened one without it. Bugs, not ours: the check ignores the cursor, so a sword picked up in the inventory brings a wooden one and
  the two stay; a sword swapped in from a chest by a number key leaves the wooden one beside it; shift-clicking the
  wooden sword into a chest gave a second one. A better sword over a bought one was not bought; it would go in like
  any item, by the rule above. Captured 2026-09-28 (4v4v4v4, self #60): buying stone holding the
  wooden one put the stone in its slot (81, hotbar 0); buying iron holding that stone put the iron in 81 and moved the stone to 82, the first empty slot; buying stone holding the iron put it in 85, the first empty. So a better sword than one carried takes that one's slot, the held slot first, the beaten one moving to the first empty slot; a worse one goes in like any item. Ours (2026-09-28): Grotto `Kit.ranked`, the order within a role, under `Gear.rank`; and the wooden sword's check no longer counts the cursor, at the user's call: a better sword held on the cursor leaves it, and only once clicked into the inventory, or onto the wooden sword's own slot, does the wooden one go. That was the chest move the server seemed to cancel: the wooden sword vanished the tick a stone sword was picked up from the chest, before it was put anywhere. Ours settles after every pickup, click and drop.
- Armor can be bought with a full inventory. Other purchases are refused: red `Purchase Failed! Your
  inventory is full!` with `mob.endermen.portal` (cap, wool).
- Pressing drop on a permanent item moves it to its Hotbar Manager slot; permanent items cannot go in
  chests. Ours (2026-09-28): drops are on, but the kept pieces (the wooden sword, compass, tools and armor)
  never leave the seat, refused at the drop key and the in-screen toss (`Loadout.kept`); a 1.8 client's clicks
  settle the wooden sword a tick later, since they never reach `InventoryClickEvent`.
- **Wood skins** (the user, 2026-09-28): a player's wood is any plank or log type they picked. Wood bought
  from the shop, or any plank or log picked up, becomes their skin: a real item everyone sees, placed as
  itself. Broken wood drops as oak planks whatever was placed, a dropped stack shows as oak planks, and the
  shop shows oak planks. Same break time and blast resistance, so nothing plays differently. Ours: Grotto's
  kit takes a `Skin` (`worn` as an item comes to hand, given or picked up; `shed` as it leaves, dropped,
  spilled or broken out of a block), Tennacle's `Skins` holds the families and each player's pick, Bed Wars
  registers `wood` (the six planks and six logs, oak planks plain) and, as the user allowed, the shop shows
  the buyer's own face. The same seam takes any family (obsidian, a modern client's crying obsidian) and a
  face chosen per viewer version. The pick is `/skin` for now, a dev tool.

### 6.2 Shop window

Captured in a Solo game on 2026-09-24, with the player on Red and holding no resources:

- A 6-row chest titled after the open tab (`Quick Buy`, `Blocks`, ...). Row 0 holds the tab icons: Quick Buy
  (nether star, aqua name), then green-named Blocks (hardened clay), Melee (golden sword), Armor (chainmail
  boots), Tools (stone pickaxe), Ranged (bow), Potions (brewing stand), Utility (TNT) and Rotating Items
  (redstone torch). Every icon but the open tab's carries a yellow `Click to view!`. Row 1 is glass panes,
  lime (160:5) under the open tab and gray (160:7) elsewhere, each named dark gray `⬆ ` + gray `Categories`
  with the lore dark gray `⬇ ` + gray `Items`; clicking a pane switches tabs. Items sit in slots 19-25,
  28-34 and 37-43.
- Quick Buy page only: slot 45 is a compass named green `Tracker Shop`, slot 53 blaze powder named green
  `Hotbar Manager`; an empty Quick Buy slot is a red pane named red `Empty slot!`, lore gray `This is a Quick
  Buy Slot! ` + aqua `Shift Click` / gray `any item in the shop to add it here.`
- Item lore, in order: gray `Cost: ` then the price in the currency's color (iron white, gold gold, emeralds
  dark green); gray `Upgraded: ` + a yellow enchant where a team upgrade applies (`Sharpness I` on swords and
  axes, `Protection IV` on armor); on tools gray `Tier: ` + a yellow numeral; a blank line and the gray
  description; aqua `Shift Click to add to Quick Buy` (on the Quick Buy page, `Shift Click to remove from Quick
  Buy!`); then red `You don't have enough Iron!`. That line says `Iron` and `Gold` always, `Emerald` at a
  price of 1 and `Emeralds` above. Tools add `This is an upgradable item.`, `It will lose 1 tier upon death!`,
  a blank line, `You will permanently respawn with at` and `least the lowest tier.` An affordable item's name
  is green and its last line yellow `Click to purchase!`; an unaffordable one's name is red. An owned
  permanent item drops the cost line, keeps a red name and ends in green `UNLOCKED`.
- Buying plays `note.pling` at the buyer with the chat line green `You purchased ` + gold `<item>`; a refusal
  plays `mob.endermen.portal` with red `You don't have enough Iron! Need 24 more!`. Both sounds go out at
  volume 8 with the pitch byte at 255 and 0, which the 1.8 client clamps to 2.0 and 0.5
  (`SoundManager.getNormalizedPitch`); volume 8 only stretches their range to 128 blocks.
- The `Shift Click to add` line was missing on Permanent Chainmail and Iron Armor, Permanent Shears and both
  tools, all five in that player's Quick Buy, while other Quick Buy items kept it. The rule is unknown. Decided
  2026-09-28: ours shows it, and takes the pin, only on items not already in the player's Quick Buy.
- Items carry HideFlags 255. Potions are 373:8195 (Speed II), 373:8203 (Jump V) and 373:8206
  (Invisibility), with CustomPotionEffects (8195 is the fire resistance potion's value, which ours sends too:
  a fire resistance potion whose custom Speed II stands in for its own effect, 1.8's rule); the Dream Defender is spawn egg 383:99, the Bedbug a snowball, the
  pop-up tower a trapped chest carrying Hypixel's `ExtraAttributes` tag. The shop shows white wool and plain
  hardened clay (172) whatever the team; the wool lore says it turns into your team's color. The Permanent
  Diamond Armor lore reads `...which you will always crush with.` (sic).
- Opening the shop and switching tabs play no sound.
- Every tab click opens a new window 30 to 60 ms later, the open tab's own icon included; a purchase
  updates the open window in place, sending the cursor and the changed inventory slots (cap, 2026-09-27).
  Four single clicks 100 ms apart bought four wool, and the double click the client adds after two quick
  clicks bought nothing.
- Quick Buy is 21 per-player slots, stored as item ids (API `favourites_2`); shift-click adds or removes.
  The default fills all 21, the same in every mode (wiki, the Fandom page's shop mock-up):

  | Slots | | | | | | | |
  |---|---|---|---|---|---|---|---|
  | 19-25 | Wool | Stone Sword | Permanent Chainmail Armor | Wooden Pickaxe | Bow | Speed II Potion | TNT |
  | 28-34 | Oak Wood Planks | Iron Sword | Permanent Iron Armor | Permanent Shears | Arrow | Invisibility Potion | Water Bucket |
  | 37-43 | Golden Apple | Jump V Potion | Blast-Proof Glass | End Stone | Wooden Axe | Bow (Power I) | Bow (Power I, Punch I) |

- **Adding to Quick Buy** (cap, 2026-09-27): shift-clicking an item in a category tab opens a 6-row `Adding
  to Quick Buy...`. Slot 4 shows the item with its cost and description, the lines after them replaced by a
  blank and yellow `Adding item to Quick Slot!`; slots 19-25, 28-34 and 37-43 show the player's Quick Buy,
  each item's lore cut to yellow `Click to replace!` and each empty slot the red pane `Empty slot!` with
  yellow `Click to set!`. Names keep their affordable or unaffordable color. Clicking a slot plays
  `note.pling`, with no chat, and reopens the Quick Buy page with the item in it, its lore now ending aqua
  `Shift Click to remove from Quick Buy!` over the purchase line. Any click on an empty slot of the Quick Buy
  page says red `This is an empty Quick Buy slot!` and aqua `Shift Click on an item in the rest of the shop
  to fill it!` with `mob.endermen.portal`.
- **Tracker Shop** (`Purchase Enemy Tracker`, 4 rows): one wool per enemy team in its color from slot 10,
  named `Track Team <Team>` (red while locked), lore `Purchase tracking upgrade for your`, `compass which will
  track each player`, `on a specific team until you die.`, `Cost: 2 Emeralds` and red `Unlocks when all enemy
  beds are destroyed!`; slot 31 is an arrow named green `Go Back`, lore `To Quick Buy`, or `To Tracker &
  Communication` when opened from the compass. It lists only the teams still in the game, one in a
  two-player game. Once every enemy bed is gone the last lore line turns red `You don't have enough
  Emeralds!`, or yellow `Click to purchase!` under a green name (cap, 2026-09-27). Every click closes the
  window, with no sound: a locked tracker says red `Not all enemy beds are destroyed yet!`, an unaffordable
  one red `You don't have enough Emeralds!`, and a purchase green `You purchased ` + gold `Red Tracking`,
  then red `You will lose ability to track this team when you die!`.
- **Tracking** (cap, 2026-09-27): a bought tracker points the compass at the nearest player on the tracked
  team (our play). Once a second, in one tick, the server sends the compass target, that player's block
  (their position floored: 7 of 10 hurts on them landed in the block last sent, the rest one block off
  mid-fight), and the action bar white `Tracking: ` + the player's name in bold in their team's color +
  white ` - Distance: ` + green bold `<n>m`, in whole meters (1 to 43 seen). The first went out 0.55 s
  after the purchase. The action bar shows whatever the player holds, and the compass keeps its name. In
  38 of 56 seconds a second action bar followed within a tick, the same or a meter off. Before a tracker
  the compass points at the nearest teammate in team modes (our play); in Solo, with no teammate, at the
  game world's spawn, -344, 4, 1326 on Sky Rise, far off the map (cap).
- **Compass menu** (cap, 2026-09-27): right-clicking the compass opens a 3-row `Tracker & Communication`. In
  Solo it holds the Tracker Shop alone (compass, slot 13); in Doubles green `Quick Communications` (emerald,
  slot 11, lore `Send highlighted chat messages to` / `your teammates!`, a blank, yellow `Click to open!`)
  and the Tracker Shop (slot 15, the tracker lore and `Click to open!`). Quick Communications goes by mode,
  not by who is on the team: Doubles showed it to a player alone on their team. Only modes with more than
  one player a team have it (our play).
- **Quick Communications** (5 rows): green-named messages, `Hello ( ﾟ◡ﾟ)/!` and `I'm coming back to base!`
  (books), `I'm defending!` (iron bars), `I'm attacking!` (iron sword), `I'm collecting resources!` (diamond)
  and `I have resources!` (chest) in slots 10-15; `Thank You!` and `Get back to base!` (books), `Please
  defend!` (iron bars), `Let's attack!` (iron sword), `We need resources!` (diamond) and `Player incoming!!`
  (feather) in 20-25. Each ends in a blank and yellow `Click to send!`, the attack ones after gray `You will
  be able to select the Team.`, the resource ones after `You will be able to select the` / `Resource.`. Slot
  40 is `Go Back`, `To Tracker & Communication`.
  - Sending closes the window and gives the team green bold `TEAM > `, the sender's ranked name, white `: `
    and the message in green: the item's name less one trailing `!` (`Player incoming!`, `Thank You`), with
    `random.orb` at volume 1, pitch 1.0.
  - An attack or resource message closes the window and opens a 4-row `Select an option: ` (trailing space)
    0.1 to 0.15 s later. For teams, a wool per enemy team from slot 10, named green `I'm attacking ` or
    `Let's attack ` + the team's name in upper case, bold, in its color; for resources, a diamond, iron
    ingot, gold ingot and emerald in slots 10, 12, 14 and 16, named green `We need `, `I'm collecting ` or
    `I have ` + aqua `DIAMOND`, white `IRON`, gold `GOLD` or dark green `EMERALD`, bold. Their lore: gray
    `Click to send the message: "` + the message + gray `"` / `to your teammates!`, a blank, yellow `Click
    to send!`. Slot 31 is `Go Back`, `To Quick Communications`.
  - Most messages also aim the compass: the sender reads a green line, with `random.orb`, just before the
    team line. `Targeting your base on the compass now!` for coming back, defending, get back and please
    defend; `Targeting Gray on the compass now!`, all green, for attacking a team; `Targeting ` + `Diamond `
    in the resource's color, bold, + `on the compass now!` for every resource message. Let's attack sent
    none (once), nor do Hello, Thank You and Player incoming. Where the compass then points is not
    captured: the tool did not log the spawn position packet a 1.8 compass follows until after this game.
- **Ours (2026-09-28)**: Bed Wars' `Compass`, `TrackerShop` and `QuickComms`, Canvas screens with the captured
  titles, slots, names and lore, the compass riding Tennacle's `Lineup.aim`. The compass follows its target
  every tick and the readout refreshes four times a second, not once. A bought tracker holds the compass
  until the death that ends it; without one, a quick message's point holds until a death, else the nearest
  living teammate, or the seat's own spawn in Solo (not the world spawn off the map). A message sent while
  tracking still reaches the team but leaves the compass, so no `Targeting` line. `Let's attack` points at
  the team as `I'm attacking` does. The points: your base or a team's is its bed while it stands, else its
  spawn; iron and gold the side's own forge; diamonds and emeralds the nearest generator. The option lore's
  closing quote stays gray (Hypixel's takes the resource's color), the options open at once, and with no
  ranks the team line names the sender in gray.
- **Hotbar Manager** (6 rows, API `favorite_slots`): slots 10-17 hold the categories to drag, Blocks, Melee,
  Pickaxe, Axe, Shears, Ranged, Potions and Utility (tools split in three, no armor), each with the lore `Drag
  this to a hotbar slot below to`, `favor that slot when purchasing an`, `item in this category or on spawn.`
  and yellow `Click to drag!`; row 2 is gray panes `⬆ Categories` over `⬇ Hotbar`; slots 27-35 are the nine
  hotbar slots, each showing its category's icon with `<Category> items will prioritize this slot!` and yellow
  `Click to remove!`; slot 48 is `Go Back`, slot 50 a barrier named red `Reset to Default`, lore `Reset your
  hotbar to the default.` The slots apply on purchase and on spawn; number keys over an item buy it straight
  into that slot. Clicking a category puts it on the cursor, clicks outside the window keep it there, and
  closing the window clears it (cap, 2026-09-27).
- Rotating Items tab: one to three rotating items weekly from a pool of about 30. Defer. The week of
  2026-09-25 (cap): Block Shuffler, an Enderman spawn egg whose lore reads `Spawn an Enderman to shuffle
  most` / `nearby player-placed blocks!` (2 gold in Doubles, 6 in 4s), and Sharpened Swords II, which points
  to the upgrades window. Both were still there on 2026-09-27, the Block Shuffler at 2 gold in Solo too;
  slot 49 is a paper, green `What are Rotating Items?`, saying such items may be replaced at any time.
- Chat (cap): `You purchased <Shop Name>`, `You don't have enough Iron! Need 3 more!`.

### 6.3 Special items

| Item | Documented behavior | Still needs a capture |
|---|---|---|
| Fireball | right-click launch, 0.5 s cooldown; no teammate damage; no fire on players; cannot break end stone; can be deflected by a hit or an arrow and the deflector gets the kill; hurts its thrower; the thrower gets Slowness II for 20 ticks, and an arrow of theirs spawns with it along its line at 1.5 (cap, 3 of 3 throws). A launch within 0.5 s of the last is refused with red `Please wait 0.5s to use that again` (cap, Ironclad: refused at 0.20 and 0.40 s, launched at 0.60 s) | measured already (Polyp `hypixel`). The deflector gets the kill, being the fireball's owner from then on (our play). Ours (2026-09-28, `games.items.Fireballs`): the throw's Slowness II for 20 ticks as `fireball.slowness.ticks` and `fireball.slowness.level` (0 = none), sent as the effect the wire shows: the Sky Rise log has `entity_effect self Slowness amp 1 20t particles shown` 7 ms before the fireball's spawn (the capture script decodes no attribute packet, so the log is silent on the modifier a 1.8 client needs beside the effect: it applies a potion's speed change only from the server's `entity_properties`, `EntityLivingBase.onNewPotionEffect` running server side only). The arrow that rides along is not built: an implementation artifact of Hypixel's deflection, judged a flaw. The whole of what the thrower is sent (same log): the effect, the fireball's spawn with its acceleration and a velocity of about 0.5 a tick, the arrow's spawn at 1.5, and the hand's `set_slot` one fire charge down; no velocity to the thrower, so there is no recoil to feel. The slowdown the user never felt (2026-09-28) was ours: Polyp's self-echo filter dropped every attribute packet bound for the player while one of their own packets was being handled, aimed at the sprint toggle's echo, and a throw is a use-item packet's, so the Slowness push never left the server. The filter now drops only an echo that changes nothing but the sprint modifier (`OptimizedPlayer`, `SelfAttributeEchoTest`; StructuresTest throws under it). Our throw's wire otherwise matches Hypixel's velocity for velocity; the spawn's acceleration field differs (0.5 to their 0.1) and the user calls the flight right, so it stays. The 0.5 s cooldown is ours too since 2026-09-28 (`fireball.cooldown`, 10 ticks, the same red refusal and no charge spent, `games.items.Throttle`) | none |
| TNT | ignites when placed; custom knockback; full damage and knockback to the user; stacked TNT spreads out; a red particle above a carrier's head | measured already (fuse 50 ticks, Polyp `hypixel`): it is primed as placed, needs no lighting, and never breaks beds, which count as map blocks (our play) | none |
| Bridge Egg | lays a wool bridge along its path; a direct hit knocks back; counts for void credit. The rule is below (Bridge Egg, exactly). A throw within 1 s of the last is refused with red `Please wait 1.0s to use that again`, whatever is left (cap: refused at 0.65 and 0.90 s, thrown at 1.00 s) | none |
| Bedbug | snowball that spawns a silverfish where it lands, 15 s by the lore (20 s in a 2018 beta note). Captured (Sky Rise 2026-09-25, 56 bedbugs): health 8, named `<Team> Silverfish` in the team color with the team word bold, ambient potion swirls of color 9776938: Strength II and Resistance I, two parts to one (the damage confirms the Strength II). The thrower reads green `Your creature will fight for you for 15 seconds.` An invisible armor stand that follows it 1.38 below shows `[■■■■■]`, dark gray brackets, the squares in the team color. Both go 15.05 to 15.14 s after the spawn. Our melee hits did vanilla damage times 0.8, its Resistance I: a fist 0.8 (1.2 on a crit), a wooden sword 4 (6 on a crit), with Sharpness II 6 (cap, 23 hits); fireballs and TNT hurt it as in vanilla (our play). It walks like a vanilla silverfish (0.14 blocks a tick) and hits every 20 ticks with vanilla knockback (0.3608 up from standing). Its hits respect armor: of the 169 hurts we took while a bedbug was alive, 139 fit exactly 2.8 before armor, a vanilla silverfish's 1 times Strength II's 3.6 scaled for Easy difficulty (x / 2 + 1), then vanilla armor (13: leather over diamond legs and boots) and Protection IV's random 10 to 20. Most of the rest were bigger than a bedbug can deal. With armor ignored none fit (`research/bedwars/creature_damage.py`). The armor test (cap, Deadwood 2026-09-25): leather without Protection took 2.02 a hit, vanilla's 2.02; with Protection IV, leather, chainmail, iron and diamond took 0.89, 0.74, 0.66 and 0.55 against vanilla's 0.81, 0.72, 0.63 and 0.54. 72 of 74 fit, counting the helmet's Protection I from the purchase (Reinforced Armor, below). Death line `<a> was killed by <b>'s Bedbug.`, the owner's part in the owner's color. It targets every team but the owner's, with vanilla attack reach (our play). It sets off after an enemy from vanilla's 16 blocks: one chased us from 16.2, another that landed 17.6 away closed in only once we were inside about 15 (cap, Holmgang 4s 2026-09-25). The user (2026-09-28): a kill by a player's Bedbug or Dream Defender hands the owner NONE of the dead's items. Ours (2026-09-28, `Creatures` over Polyp's `mechanics/mobs`): the snowball's hit spawns the silverfish where it lands with real Strength II and Resistance I (their blend is the captured swirl), health, effects and life as `creatures.bedbug.*` rules, the stand 1.38 below counting five squares down over its life (ASSUMED: the capture saw them full), its side the owner's for every check, its kill credited to the owner through `Lives.blame`, the line `<a> was killed by <b>'s Bedbug.`, and the loot rule `combat.death.to-owner`, nothing by default | none |
| Dream Defender | iron golem for 4 minutes, reduced health, damage respects armor, your own bows cannot hurt it, sees invisible players. Captured (Sky Rise 2026-09-25, ours and an enemy's): health 20, and our melee hits did plain vanilla damage: a fist 1 (1.5 on a crit), a wooden sword 5, with Sharpness II 7.5 (cap, 15 hits), and fireballs and TNT as in vanilla (our play); ambient potion swirls of color 5134171 (two parts Weakness to one of Slowness). Green `Your creature will fight for you for 240 seconds.` An invisible armor stand 0.94 above it counts down in 0.1 s steps, `240.0s [■■■■■■■■■■]`: the time in the owner's team color, dark gray brackets, then one square per 2 health left in the team color and the rest gray (health 18.5 showed 9 and 1, 11 showed 5 and 5, 3.5 showed 1 and 9). The count drops its leading zero (`.1s`, `.0s`, `-.0s`) and the golem goes at 240.1 s. It swings every tick while in reach (status 4 and `mob.irongolem.throw` at volume 1, pitch 1.0), and a hit lands every 9 ticks on a target that stays in reach (31 of 79 gaps; vanilla's hurt window would give 10), with vanilla knockback plus the golem's 0.4 throw: 0.4 across and 0.7608 up from standing, 0.8 when rising. Its damage respects armor: 78 of 79 hits on us fit exactly vanilla's golem roll, 7 + nextInt(15), scaled for Easy difficulty (x / 2 + 1, so 4.5 to 11.5), then vanilla armor and Protection IV's random 10 to 20. 56 of the hits came in leather (armor 7), 23 after we bought diamond (armor 13), and the mean fell from 1.98 to 1.54. With armor ignored 5 of 79 fit; on Normal, 47 (`research/bedwars/creature_damage.py`). An armor test (cap, Deadwood 2026-09-25: diamond legs and boots at Protection II, III and IV, hits under a golden apple's Absorption left out) fit all 29, averaging 2.84, 2.45 and 1.59 a hit against vanilla's 2.92, 2.46 and 1.54; with armor ignored, 1. It moved at a median 0.15 to 0.16 blocks a tick (few samples). Death line `<a> was killed by <b>'s Dream Defender.` It targets every team but the owner's, with vanilla attack reach (our play). It sets off after an enemy from vanilla's 16 blocks (cap, Babylon Solo 2026-09-25, open ground): it stood still while we walked in from 27 blocks and set off at 16.1, stopped when we backed out to about 18, and came again once we were back inside 13 to 16 (`research/bedwars/creature_aggro.py`). An earlier try on Holmgang had the golem shut in a pen, its only gap 2 blocks high for a 2.7-tall golem. Ours (2026-09-28): the egg's use on a block face puts the golem on that face; `creatures.defender.*` rules carry health 20, life 240 s, speed 0.27 (fitted to 0.15 to 0.16 a tick), a swing every tick with the hurt window pacing the hits, sight of invisible players; the swirl color is set straight on it (no real Weakness or Slowness: its damage is the roll, its speed the rule); the stand 0.94 above shows `240.0s [■■■■■■■■■■]` in the captured shape; its own side cannot hurt it by hand or bow; kills and loot as the Bedbug's | none |
| Compact Pop-up Tower | returns to the inventory if placed badly; not below the build limit. The build is below (Compact Pop-up Tower, exactly) | none |
| Magic Milk | for 30 s the drinker triggers no trap at all: no effect, no alert and no line for anyone (our play); only the drinker is told red `Your Magic Milk wore off!` with `liquid.water` (volume 0.7, pitch 2.0) when it ends (cap, four drinks: 30.0 to 30.1 s after the milk was used up, as the shop lore's `Avoid triggering traps for 30` / `seconds after consuming.` says). An intruder still inside when it wears off sets the next trap off at once (0.25 and 0.35 s later, cap). Cleared on death; keeps other effects per the docs, though drinking it removed the Mining Fatigue a trap had just given (cap). Ours (2026-09-28, `MagicMilk`): the drink washes off what traps inflict (blindness, slowness, mining fatigue) and keeps every other effect, where plain milk would clear the team's Haste too; `magic-milk.duration` 600 ticks from the end of the drink; the wearing off rechecks the drinker against every trap on the spot, with no delay | none |
| Sponge | not vanilla's (cap, 18 sponges, 2026-09-25): placed with the normal sound, then from 5 ticks after, every 8 ticks for 4 steps, it clears the next ring of a 9 x 9 x 9 cube around itself (ring k at step k, the cube's shell k blocks out), with `random.wood_click` at volume 0.5 and pitch bytes 110, 116, 123, 129, and `cloud` x4 (spread 0.08, speed 0.04, long-distance) at the ring's open cells. One step after the last it vanishes with `game.player.swim.splash` (volume 0.6, pitch 2.0), 37 ticks after it went down. While it is active the cube takes no water flowing in, like a base region, so water placed beside it turns still at once and stays that way until a neighbor changes: one stayed floating over a hole dug under it even after the sponge was gone (our play, cap). Ours (2026-09-28, `Sponge`): the block lands as any placement, then `sponge.delay` 5, `sponge.step` 8 and `sponge.radius` 4 drive the rings, each with the click at the sponge and a cloud at every open cell of the shell, sent whole (the capture's 30 clouds a tick is Hypixel's sender, not the rule); its cube is held still through `Building.still` and let go when it vanishes, so nothing stays floating afterwards | none |
| Ender Pearl | no landing damage; lands on the last safe 2-tall spot unless over the void (2026-05); cannot land in a shopkeeper's space | done: Polyp's `hypixel` preset has Hypixel's pearls, with a BedWars variant for the sounds (our play) | none |
| Invisibility | hides armor and nametag from enemies; a teammate sees the armor and the nametag but not the body (our play); held items stay visible; footstep particles on 1.8, mycelium on latest clients; louder footsteps; removed by enemy damage but not a teammate's arrow; strips stuck arrows. Captured (Sky Rise 2026-09-25): the effect is Invisibility amplifier 1 for 600 ticks. An enemy who drank it had all four armor slots sent empty in the tick of the drink, while his held wool still showed after. He left `footstep` x1 at his feet plus 0.1 (spread 0.3, 0, 0.3, speed 1, long-distance) as he walked, 1 to 25 ticks apart; a drinker is sent their own footsteps too. Our first hit on him sent the armor back in the tick of the hurt. A drinker who takes damage loses the effect in that tick and reads red `You took damage and lost your invisibility!`. The user (2026-09-28): a fall keeps it; fire breaks it; an enemy's blast breaks it, your own fireball jump does not, and a teammate's fireball neither hurts nor pushes you. Ours (2026-09-28, `games.Invisibles` over Polyp `ViewRewrites` and Grotto `Nametags.veil`): the armor goes out empty to every viewer not on the drinker's side in the tick of the drink and comes back with the hit, held items untouched; the nameplate sits on a nameless twin scoreboard team for the other teams (teammates and watchers keep it); the potion's swirl is off; fire and an enemy's hit, arrow or blast break it with the captured line, a fall or your own or a teammate's blast do not (`invisibility.hides-armor`, `invisibility.hides-name`, `invisibility.breaks-on-damage`); a teammate's blast is inert like their swing (`combat.friendly-fire.allows` gains `explosions`, off by default, a blast or a thrown thing attributed to its igniter or shooter). The swirl other players saw came from Polyp's blend of every active effect; 1.8's `calcPotionLiquidColor` skips an effect with particles off, and so does ours now. The bottle other players saw floating in the drinker's hand was our own armor resend carrying the hand mid-drink; only the four worn slots go out now, as captured. Footsteps are open: a 1.8 client's `footstep` particle has no modern particle Via could carry (ViaRewind lists it for 1.8 to 1.7 only), and a modern client's mycelium is unbuilt | footsteps |
| Speed II, Jump V | The user (2026-09-28): a drunk potion leaves no bottle, in Bed Wars and in MineMen's NoDebuff alike; ours is the rule `consumables/minecraft:potion/remainder` off (Polyp `ConsumableTypeConfig.remainder`, a type at a time). (cap, 2026-09-25) Speed amplifier 1 and Jump Boost amplifier 4, 900 ticks each with particles shown. 15 s after the drink the server sends the effect again at 600 ticks, and removes it 45 s after the drink (four drinks). The rule behind it: a timed effect is sent again whenever its time left reaches a multiple of 30 s, so a golden apple's Absorption comes again at 90, 60 and 30 s left (cap, Deadwood) | none |
| Golden Apple | vanilla 1.8 (our play), confirmed (cap, Ironclad 2026-09-25, two eats): 32 ticks to eat (1.61 and 1.63 s), `random.burp` at volume 0.5, then Absorption I for 2400 ticks and Regeneration II for 100, particles shown | none |

#### Bridge Egg, exactly

Fitted on 95 eggs and 2,300 wool packets (cap, 2026-09-25). With one fixed 5-tick delay and nothing tuned per
packet, the rule reproduces 92% of full three-block packets exactly, and 95% of shorter ones fall inside it. The
misses sit on cell boundaries, where the spawn packet's 1/32-block rounding of the start decides, or on cells
that were not air. Packets that seemed to fit a 6-tick delay all arrived late in a tick: the delay is 5.

- **Flight**: a vanilla 1.8 egg. Each tick it moves by its motion, then the motion is multiplied by 0.99 and y
  loses 0.03; it leaves at the vanilla throw speed of 1.5. The velocity packets every 10 ticks
  match this to the fourth decimal. It is removed after 30 ticks, on hitting a player or a block, or on
  leaving the build limits, and no wool goes outside the build limits (our play).
- **Wool**: every tick, for the position (x, y, z) the egg had 5 ticks earlier, the thrower's team wool goes
  into each air block among `floor(x + dx), floor(y - 1.5), floor(z + dz)` for (dx, dz) in (0, 0), (±0.5, 0),
  (0, ±0.5). That is the block under the egg plus its x neighbor and its z neighbor on the sides the egg is
  nearer: an L of three, fewer where a cell is already wool. A straight throw leaves a bridge two wide, a
  diagonal one steps between two and three, and a vertical throw builds a column three blocks a level.
- **Start and end**: the first wool is the egg's position one tick into its flight, laid about 6 ticks after the
  throw, so the bridge begins about 1.5 blocks from the thrower. Wool keeps going down for about 4 ticks after
  the egg is removed. A full flight lays 34 to 37 blocks, about 80 wool.
- **Sound**: `mob.chicken.plop` at volume 1, pitch 1.0, at each laid block's corner.
- **Ours** (2026-09-28, `BridgeEgg` over `games.map.Structures`): Polyp's own egg flies; from the next tick a
  job records where it is once it has left the throw (the user felt the bridge start under the thrower, twice:
  the first skip compared against the egg's position inside the launch event, which is zero there, so the
  throw's own cell was still laid; since 2026-09-28 (later) it is the event's spawn point, within a 1/32 for
  the wire grid, and a real launch is tested), lays the L of three for the position `bridge-egg.delay` (5) ticks back through
  `Building.place` (the same refusal as a hand placement, air only, marked as the thrower's own), and removes the
  egg past `bridge-egg.life` (30) ticks or once it leaves the mode's build space; the queue drains after. One throw
  a `bridge-egg.cooldown` (20 ticks); a refused throw reads `Please wait 1.0s to use that again` and keeps its egg.

#### Compact Pop-up Tower, exactly

Fitted on 19 builds (cap, 2026-09-24): after turning each to one frame, the clean ones match the blueprint at 96 to
100 of the 100 blocks above the floor; the rest were cells that were already solid.

- **Use**: right-clicking a block face spends one tower. No chest is placed: the clicked block is sent back as it
  was. From the next tick the server places the blueprint two cells a tick, bottom layer first, 110 cells in 55
  ticks (2.75 s). A cell that is not air is skipped but still takes its slot, so every build ends 55 ticks
  after the click. Each placed block plays `mob.chicken.plop` at volume 0.8, pitch byte 50 (0.79x), at the
  block's corner plus 0.5 along the horizontal axis across the tower's facing (z for a tower facing along x,
  x for one facing along z).
- **Frame**: P is the block the chest would have gone into, and the tower takes the facing the chest would
  have been placed with: the placer's look rounded to a side, `floor(yaw * 4 / 360 + 0.5) & 3`, turned to face
  them, so the doorway faces the placer (cap, 11 of 11 towers, all four facings). `f` counts forward, away from
  the placer, `r` to the placer's right and `h` up from P; the ladder column is P itself.
- **Push**: a player a block is placed into gets the velocity `-0.5 * look + (0, 0.2, 0)`, where look is that
  player's own look vector `(-sin yaw * cos pitch, -sin pitch, cos yaw * cos pitch)`: straight back from where
  they face, and up by more the further down they look. It holds to within 0.0004 on every push with a fresh
  look (cap, 2026-09-25), from our own towers and another player's, and is sent again for each further block
  that lands in them.
- **Blueprint** (team wool unless a ladder):
  - `h = -1 to 3`, the walls: (f, r) = (-2, -1), (-2, 1), (-1, -2), (-1, 2), (0, -2), (0, 2), (1, -1),
    (1, 0), (1, 1), plus (-2, 0) at `h = 2 and 3` only: the doorway is (-2, 0) at `h = 0 and 1`. At `h = -1`
    P itself is wool; at `h = 0 to 4` it is a ladder on the back wall, facing the door.
  - `h = 4`, the platform (30): r = -2, 0 and 2 for f = -3 to 2; r = -1 and 1 for f = -2 to 1; (-2, ±3) and
    (1, ±3). P is the ladder.
  - `h = 5`, the railing (18): f = -3 and f = 2 for r = -2 to 2, and r = ±3 for f = -2 to 1.
  - `h = 6`, the crenels (10): (-3, -2), (-3, 0), (-3, 2), (2, -2), (2, 0), (2, 2), (-2, ±3) and (1, ±3).
- **Order**: layer by layer from `h = -1` up (10, 10, 10, 11, 11, 30, 18, 10 cells). Within a layer the cells go
  from the lowest world x to the highest, whichever way the tower faces (three facings seen).
- **Ours** (2026-09-28, `PopupTower` over `games.map.Structures`): the trapped chest's placement is cancelled once
  `Building` has allowed it (a refusal never reaches the tower, so the chest returns and nothing is spent), one
  tower leaves the hand, and the blueprint above goes up `tower.per-tick` (2) cells a tick from the next tick,
  each through `Building.place` (a taken cell keeps its slot), with the plop at the captured corner and the
  `-0.5 * look + 0.2 up` shove through Polyp's knockback delivery for anyone a cell lands in.

Explosions, as documented:

| Block | TNT | Fireball |
|---|---|---|
| Blast-Proof Glass | immune | immune |
| End Stone | breaks | does not break |
| Obsidian | no | no |
| Wool, clay, planks, ladder | breaks | breaks (clay was disputed; see below) |
| Anything in or under water | protected | protected |
| Map blocks | never | never |
| Beds | never: map blocks (our play) | never |

**Explosives are the most settled part of BedWars.** Polyp's `hypixel` preset (and its `bedwars()` variant,
`HYPIXEL_BEDWARS`) was fitted on our own Hypixel captures: TNT fuse 50 ticks detonating at the feet and lit on
place; the BedWars fireball at power 2, launched at 0.5 for two ticks then 1.0449, no direct damage, passing
through its owner and detonating a tick late; flat 2.0 blast damage that ignores armor points; knockback
fitted on 240+ captures; fire only where a block broke; the BedWars pearl landing (130 throws). Its rays are
vanilla 1.8, with full glass blocks vetoed and casting a hard shadow.

That also settles the table. A power-2 fireball ray starts at 1.4 to 2.6 and pays `(resistance + 0.3) * 0.3`
per block, so end stone (9.0, 2.79 a block) never breaks while clay (4.2, 1.35), planks, wool and ladders do:
the clay dispute goes to "breaks". TNT at power 4 (2.8 to 5.2) clears end stone next to the blast, water (100)
shields what sits behind it, and obsidian never breaks. The only custom block rules are blast-proof glass and
map-block protection.

## 7. Team upgrades and traps

Paid in diamonds at the upgrades NPC (6-row `Upgrades & Traps` window).

| Upgrade | Effect | Solo/Doubles | 3s/4s and 4v4 |
|---|---|---|---|
| Sharpened Swords | Sharpness I on all swords and axes; Sharpness II as a rotating tier | 4, 16 | 8, 32 |
| Reinforced Armor I-IV | Protection I to IV on all armor | 2, 4, 8, 16 | 5, 10, 20, 30 |
| Maniac Miner I-II | Haste I, Haste II | 2, 4 | 4, 6 |
| Forge I-IV | +50%, +100%, emeralds, +200% | 2, 4, 6, 8 | 4, 8, 12, 16 |
| Heal Pool | Regeneration I in your base | 1 | 3 |
| Cushioned Boots I-II | Feather Falling I, II on boots | 1, 2 | 2, 4 |

- Dragon Buff (5, a second dragon) was retired on 2025-01-29 for Cushioned Boots and the automatic
  45:00 dragon.
- Sharpened Swords' Tier 2, Sharpness II, is a rotating item: its lore adds dark gray italic `Tier 2 is a
  Rotating Item!`. It first showed in the capture of 2026-09-25 14:21 UTC and in every one after, never
  before, so it came with that week's rotation. The other 3s/4s upgrade prices in the table match the live
  window (cap, Lasagne and Relic 2026-09-25).
- Maniac Miner II arrives as Haste II for 72000 ticks, removed and sent again every second (cap,
  2026-09-25, from the Max Team Upgrades modifier).
- Reinforced Armor (cap, Deadwood 2026-09-25): each tier re-sends the armor with the new Protection 0.3 to
  0.4 s after the purchase. At tier I the helmet's new enchant reached the client only at the next respawn,
  yet it protected from the purchase: 13 of 13 bedbug hits in between fit Protection I on four pieces, 8 of
  13 on three. Tiers II to IV re-sent all four at once. The tier I helmet is a Hypixel bug, recorded here
  and not reproduced: ours sends all four pieces at every tier.
- A purchase is broadcast to the team (cap): green `<player> purchased `, gold upgrade name. Traps use the
  same line.
- Heal Pool (cap, Doubles on Sky Rise and Amazon 2026-09-25): Regeneration I while the player's feet are
  within 21 blocks of the team's spawn point, a sphere, removed outside it. It is sent with duration 32767,
  which a 1.8 client shows as `**:**`. On Amazon, 27 changes from 12 blocks below the spawn to 17 above came
  on at 20.92 blocks or less and went off at 21.11 or more from the spawn teleport point. That point
  separates them best of any center within 4 blocks; its block center and the eye height do not separate
  them at all, and the bed is 15 blocks away. Sky Rise agrees: a circle through its 13 crossings level with
  the spawn centers 0.3 blocks from it, radius 20.9. The check runs once a second, every change landing on
  the same tick of a 20-tick beat. While inside, the effect is sent again 246 ticks after each entry, then
  every 30 s (cap, Ironclad: ten times, 30.03 to 30.08 s apart), which changes nothing on screen. It works without a bed (our play). The older claims were 12 blocks (2017) and 5
  around the bed (guides). Its particles (cap, Amazon 2026-09-25): `happyVillager` x39 at the team's spawn
  point, spread 10.5 on every axis, speed 0, long-distance, every 12 ticks, two identical packets each time.
  Every team's pool shows to anyone in range.
- **The window** (captured 2026-09-24, Solo, twice: once with every upgrade bought by the host's modifiers,
  once from scratch). Upgrades in slots 10-12 and 19-21: Sharpened Swords (iron sword), Reinforced Armor
  (iron chestplate), Maniac Miner (golden pickaxe), then the forge (furnace), Heal Pool (beacon) and Cushioned
  Boots (diamond boots). Traps in 14-16 and 23: Blindness (tripwire hook), Counter-Offensive (feather), Reveal
  (redstone torch), Miner Fatigue (iron pickaxe). Row 3 is gray panes `⬆ Purchasable` over `⬇ Traps Queue`;
  slots 39-41 are light gray stained glass (95:8, stack of 1, 2 and 3) named red `Trap #1: No Trap!`, with
  `The first enemy to walk into your` / `base will trigger this trap!`, `Purchasing a trap will queue it here.`
  / `Its cost will scale based on the` / `number of traps queued.` and `Next trap: ` + aqua `1 Diamond`.
- A tiered upgrade's name carries the tier it sells or last sold (`Reinforced Armor I` before any, `Reinforced
  Armor IV` when maxed; the forge reads `Iron Forge`, `Golden Forge`, `Emerald Forge`, `Molten Forge`), and its
  icon's stack size counts the tiers bought. Its lore lists each tier as `Tier 1: Protection I` + gray `, ` +
  aqua `2 Diamonds`, gray until bought and green after. Single upgrades show `Cost: ` + aqua `4 Diamonds`.
  The name is yellow and the last line yellow `Click to purchase!` when affordable (yellow here, green in the
  item shop), red with red `You don't have enough Diamonds!` when not (`Diamonds` even at 1), and green with
  green `UNLOCKED` once bought. The Solo/Doubles prices above all match.
- Buying broadcasts green `<player> purchased ` + gold `<upgrade>` to the team, with `note.pling` as in the
  item shop; an unaffordable click says red `You don't have enough resources to unlock that upgrade!` with
  `mob.endermen.portal`. Clicking again 200 ms after the last click got red `Clicking too fast!` with the
  same sound (cap, Holmgang), and so did two clicks 101 ms after the last, each closing the window (cap,
  Deadwood 2026-09-27), while clicks 299 ms and more apart went through. Only clicks on upgrades and traps
  count: faster clicks on the panes and the empty queue slots said nothing, and the item shop has no such
  limit (6.2). A purchase or a trap removal reopens the window about 0.2 s after the click, where the item
  shop updates in place (cap, 2026-09-27). A queued trap fills its queue slot as green `Trap #1: Miner
  Fatigue Trap` with its description, `The first enemy to walk into your` / `base will trigger this trap!`,
  gray `Queued by: ` + the buyer's name in aqua, and yellow `Right Click to remove! ` + dark gray `(No
  refund)`; removing it says red `Removed ` + gold `Miner Fatigue Trap` + red ` from the queue!`.
- A trap going off (cap): the title red `TRAP TRIGGERED!` over `Your Miner Fatigue Trap has been set off!`
  (fade 5, stay 35, fade out 10, after a 0/5/0 reset), bold red `Miner Fatigue Trap was set off!` in chat and
  `mob.endermen.portal` at volume 8, pitch byte 0, all in one tick.
- Lore as sent: Sharpened Swords `Your team permanently gains` / `Sharpness I on all swords and axes!`; the
  forge `Upgrade resource spawning and` / `capacity on your island.`, its tiers `+50% Resources`, `+100%
  Resources`, `Spawn emeralds` and `+200% Resources`; Heal Pool `Creates a Regeneration field around` / `your
  base!`; Blindness Trap `Inflicts Blindness and Slowness for` / `8 seconds.`; Counter-Offensive Trap `Grants
  Speed II and Jump Boost II` / `for 15 seconds to allied players` / `near your base.`; Reveal Trap `Reveals
  invisible players as well as` / `their name and team.`; Miner Fatigue Trap `Inflict Mining Fatigue for 8
  seconds.`

Ours (2026-09-28), keeping the rules and leaving the artifacts (decided: parity is behavior, not Hypixel's
implementation): a purchase repaints the window where Hypixel reopens it 0.2 s later; Reinforced Armor's pieces
change at once, all four at every tier, where Hypixel re-sends them 0.3 to 0.4 s later and misses the helmet at tier
I; Haste goes on for good with its particles, not re-sent every second; the Heal Pool comes on and off as the
player crosses its edge, where Hypixel checks once a second, and is never re-sent while it holds; "Clicking too
fast!" stays, at 300 ms, since it guards against a double purchase. The line for a click on a maxed upgrade, "You
have already maxed out this upgrade!", and "Trap queue full!" are Hypixel's strings (the string table), their color
assumed red; a trap refused without a bed says the lore's red "Can't be purchased without a bed!".

**Traps**:
- Queue of three, first in first out. The next trap costs 1, 2 or 4 diamonds by how many are queued.
  One trap fires per intruder entering the base. They never fire once the bed is gone, and since 2025-09
  cannot be bought then. A queued trap can be removed by its buyer, without a refund.
- Blindness Trap: Blindness II for 8 s and Slowness I for 5 s on the intruder (cap, 2026-09-25).
- Counter-Offensive: goes off on the same 12-block sphere around the bed as the others, then gives its
  effects (our play): Speed II and Jump Boost II for 300 ticks, particles shown, in the tick of the set-off
  (cap, Waterfall 2026-09-25). The owner's title reads plain `Your Counter-Offensive Trap has been set off!`
  under `TRAP TRIGGERED!`, with the usual chat line and `mob.endermen.portal`.
- Reveal (Alarm) Trap: strips invisibility, announces the intruder's name and team. Going off (cap,
  Waterfall): a clearing title, then (5/35/10) bold red `ALARM!!!` over `Reveal trap set off by ` + red
  `Red ` + `team!`; in chat bold red `Reveal trap set off by `, gray bold name, bold red `from Red team!`.
  `mob.endermen.portal` (volume 8, pitch 0) at the owner, then an alarm at the bed's head block: `note.pling`
  at volume 1 every 2 ticks for 5 s, 51 notes alternating pitch bytes 107 and 113.
- Miner Fatigue Trap: Mining Fatigue I for 8 s (cap, 160 ticks, particles shown; the I, III or "-80%" dispute
  is over). The intruder gets only the effect: no chat, title or sound.
- Set-off (cap): bold red `Miner Fatigue Trap was set off!` in chat, the title red `TRAP TRIGGERED!` over
  `Your Miner Fatigue Trap has been set off!`, and `mob.endermen.portal` (the window bullets above have the
  timings).
- Two traps go off 30 s apart (cap, Lucky Rush 2026-09-24: the second fired 30.05 s after the first, with
  the intruder still inside). On Harvest (Doubles, 2026-09-25) they fired 60 to 67 s apart only because the
  owner bought them one at a time, after each trigger. Confirmed on Sky Rise (Doubles, 2026-09-25): three
  queued at once fired at 0, +30.1 and +60.5 s while the intruder stood still inside.
- Trigger area (cap, Ashfire Doubles 2026-09-25): a sphere of radius 12 around the bed. Six walk-ins from the
  north (at the bed's height, 3.5 and 7.5 blocks above), east, south and west fit a sphere centered within
  half a block of the bed's head block, radius 12.0, every edge point within 0.2 of it; a flat circle misses
  by up to 2.5. Ashfire puts the bed 11 blocks from the spawn, so the spawn is not the center: the Heal Pool
  centers on the spawn, the traps on the bed. Sky Rise's triggers 23 to 24 blocks north of the spawn fit a
  bed about 11 blocks toward the middle there too. A trap that comes off cooldown fires at once on an
  intruder already inside (30.03 s after the previous one), and one bought with an intruder inside fired
  0.48 s after the purchase (cap, Ironclad). With several intruders a trap takes only one (our play), and
  Hypixel's pick looks arbitrary. Ours takes the intruder closest to the bed when it fires, skipping Magic
  Milk drinkers, with a tie going to whoever entered first.

## 8. Death and respawn

- **Respawn** 5 s while the bed stands (cap, eight deaths). In the tick of the death: the death line,
  Invisibility and Night Vision at duration 32767, every item removed, health and food full, the XP bar at 0,
  a clearing title (times 0/1/0, empty title and subtitle), then times 0/30/0 red `YOU DIED!` over yellow
  `You will respawn in ` red `5 ` yellow `seconds!`, the same line in chat. Title and chat repeat every
  second down to `1 second!`. Within 0.1 s the player is teleported to the pregame spawn over the middle
  (0,152,0 on Sky Rise, 0,133,0 on Harvest) facing yaw 0, and stays in survival: no spectator mode. 4.96 to
  5.00 s after the death comes a teleport to the point the game started them on, both effects removed, yellow
  `You have respawned!`, a clearing title with times 0/5/0, then times 5/30/5 green `RESPAWNED!` over an
  empty subtitle, the XP bar back, and the kit 0.1 s later. The victim hears no sound at either end. Ours
  (2026-09-28): `combat.respawn.wait` is `landing`, so the wait hangs level at the arena's spawn, the pregame
  room's. The dead and the watchers wear vanilla Invisibility and Night Vision at 32767 while they wait
  (`view.spectate.invisible`, `view.spectate.night-vision`, on; the respawn's body sheds them), everyone
  watching sees everyone by default (`view.sight.*`, the user's rule), and a fellow dead or a watcher sees the
  body itself: the invisible flag is cleared in the copy of the metadata they are sent (Polyp `ViewRewrites`,
  Tennacle `Spectators`). Nameplates stay, the roster team's.
- **Respawn invincibility** exists and ends when you attack:
  `You attacked someone and lost your invincibility!` (cap). Length: 1.5 s in 2017, then 3 s, and 3 s today
  (cap, 2026-09-25): over 11 respawns our hits up to 2.97 s after the spawn did nothing, and the first to
  land came 2.96 to 3.23 s after.
- **Resources go to the killer's inventory**, wherever the killer is, one line per resource in a fixed
  order (cap): `+5 Iron` white, `+3 Gold` gold, `+3 Diamonds` aqua, `+2 Emeralds` dark green. Void
  knock-offs pay out too (cap). With no creditable attacker they are lost. Only resources transfer.
- **Ours (2026-09-28)**: a rule, `combat.death.to-killer`, picks what the killer takes (Tennacle's `Spoils`, every
  bed mode); Bed Wars sets `only(minecraft:iron_ingot, minecraft:gold_ingot, minecraft:diamond,
  minecraft:emerald)`, the lines in that order, and bed fight nothing. Everything is one `except()` away. What falls
  where the dead stood is Polyp's `death/spill` (keep in every match) narrowed by `death/spilled`. Resources on
  the cursor or in the crafting grid count too; the grid refuses every click today, so that only matters to a
  ruleset that allows crafting. A killer still waiting on a respawn is paid at it, since the respawn's kit would
  wipe what they got, and what does not fit lands at their feet.
- **Anti-denial**: no dropping or inventory below the build limit; the cursor and crafting grid do not
  hide resources; logging out mid-fight still pays the killer, even over the void.
- **Kill credit**: a mob's kill goes to its owner, fall deaths pay the attacker, a deflected fireball
  credits the deflector, zero-damage projectiles grant void credit, a suicide right after losing the bed
  credits the bed breaker. Ours (2026-09-28): a fireball's or a TNT's kill is its thrower's or igniter's, the
  death line `was killed by` with no tool (`Lives.blame` falls back to what `FriendlyFire.attributed` names; the
  user saw such kills uncredited). The credit window is 10 s (cap, 2026-09-25): nine void deaths 3.5 to 9.96 s after
  the victim's last hurt read `was knocked into the void by`, five at 10.77 to 16.9 s read `fell into the
  void.`
- **Final kills**: a death with the bed gone eliminates. Bold aqua ` FINAL KILL!` follows the death line;
  the victim reads red `You have been eliminated!`; the last member out prints
  `TEAM ELIMINATED > <Team> Team has been eliminated!` (cap). Just before the final-kill line the victim's
  ender chest is dumped into their team's forge:
  `Contents of <player>'s Ender Chest have been dropped into their fountain.` (cap). How the stacks come out
  (spread, kick, delay) is not captured: CAPTURE a final death with a stocked ender chest. The user: they are
  not generated items, whatever they are, so they never count against the forge's cap or split on pickup
  (ours: a generator's own drops carry a tag, nothing else does, and stacks of different provenance never
  merge).
- **Spectating**: eliminated players spectate with a compass, a settings item, Play Again and Return to
  Lobby; spectators chat only with spectators. A cross-teaming warning prints (cap).
- **Disconnects**: `/rejoin` works while the bed stands; a rejoin adds a 10 s respawn; leaving counts as a
  death, final when bedless. `<player> disconnected.` (cap). Back in (cap, Sky Rise 2026-09-25):
  `<player> reconnected.`, the death state, the countdown from 10, and yellow `You will respawn because you
  still have a bed!` 0.14 s after the first count.
- **AFK**: 4 minutes then a 10 s warning in 2017; we are not testing it. The warning reads bold red `You will
  be afk-ed in ` + bold yellow `10 seconds` + bold red `!` (cap, in game), then `note.pling` (volume 1,
  pitch 1.0) every 2 ticks until the player moves, up to 10 s (cap, 14 warnings); the candidate kick lines
  are
  `You were kicked for inactivity!` and `<name> was kicked for being AFK!`.
  The red `You are AFK` title in our logs belongs to the main lobby, before a move to Limbo.
- Death lines (cap) color names by team with gray text and a period: `<a> was killed by <b>.`,
  `<a> was knocked into the void by <b>.`, `<a> fell into the void.`, and `<a> died.` for a fall with no
  attacker. Kill-message cosmetics swap the verb (dozens of variants seen). A bow kill reads `<a> was shot
  by <b>.` and a fireball kill `<a> was killed by <b>.` (cap). Each arrow hit tells the shooter gray `<a> is
  on ` red `18.1 ` gray `HP!`, with `random.successful_hit` (volume 1, pitch 1.0; cap, 32 hits). Other
  players see the dead one leave the tab list at the death, again every second while they wait, and come
  back at the respawn.

## 9. Beds

- Enemy beds break by hand with no tool; your own bed cannot be broken: red `You can't destroy your own bed!`
  (plain red, not bold; the user, 2026-09-28). Ours: `beds.break` names whose bed a seat may break (`enemy` by
  default, `own` allowed by a rule), the refusal reads the captured line; a bedless start, for a challenge, is
  for later. A seat that leaves with no bed, or whose bed falls while it is away, is out, and the game ends
  when it was the last in the way, as in bed fight (the user, 2026-09-28; `Lives`, pinned by two round tests).
  in chat, no sound, both halves sent again (cap). A bed breaks at the client's own break speed, with no
  timer of the server's (our play): with Haste II from Max Team Upgrades it went 0.2 s after the first swing,
  both halves at once (cap).
- Chat (cap): bold `BED DESTRUCTION > `, the team's bed in its color, gray `was destroyed by`, the breaker
  in their color, `!`. Your own team reads `Your Bed was destroyed by <player>!`. Cosmetics swap the verb.
- Titles for the losing team (duel capture, and BedWars 2026-09-25): a clearing title, then 5/35/10 red
  `BED DESTROYED!` over plain `You will no longer respawn!`, beside `BED DESTRUCTION > Your Bed was <verb> by
  <player>!` in chat.
- Sounds (Bed Rush Duel capture): the losing side hears `mob.wither.death`, the breaker
  `mob.enderdragon.growl`, volume 1 and pitch 1.0, right after the chat line and the titles. MineMen's bed
  fight capture (2026-09-07) plays the same pair, and its growl sits on the enemy bed 45 blocks from the
  listener: both sounds play at the bed. Bed fight does the same since 2026-09-24 (it had played the growl to
  everyone at their own position, the losers included). In Hypixel BedWars (cap, 2026-09-25) each plays at
  the listener's own block instead: the growl at the breaker's and the wither death at the victim's, both
  about 4.5 blocks from the bed, like the Bed Gone and Sudden Death sounds.
- Rewards: `+24 tokens! (Bed Destroyed)` and `+15 Bed Wars XP (Bed Break)` (cap).
- 1.14+ clients sometimes see a broken bed come back (the sneak eye height failing a server check, per
  players). Worth a test on our side.

## 10. Presentation

- **Sidebar** (cap, Solo):

```
BED WARS                  bold
08/20/26  m144Q           gray date, dark_gray server id

Diamond II in 5:22        white label, green time

R Red: ✓                  letter in team color, name white, bold green U+2713
B Blue: 1                 players left once the bed is gone, green
A Aqua: ✗                 out, bold red U+2717
W White: ✓ YOU            gray YOU on your own row
...

www.hypixel.net           yellow
```

  Four-team boards (cap, Relic 4s 2026-09-25) list the four teams, a blank line, then `Kills: `, `Final
  Kills: ` and `Beds Broken: ` each with a green count, a blank line and the site line. Tennacle's bedfight
  board is this shape already (`Boards.hypixel`).
- **Tab**: entries are a bold team letter and the name in team color, with health as a yellow number;
  kills, final kills and beds broken since 2021. In game (cap, 2026-09-25) the header is aqua `You are
  playing on ` + yellow bold `MC.HYPIXEL.NET`, and the footer aqua `Kills: `, `Final Kills: ` and `Beds
  Broken: ` each with a yellow count, over green `Ranks, Boosters & MORE! ` + red bold `STORE.HYPIXEL.NET`,
  sent again on every kill.
- **Chat**: Solo chat is global; team modes default to team chat with `/shout` on a 60 s cooldown. A
  captured Solo line reads `[49✫] [PINK] [VIP+] <name>: <message>` (level, team tag, rank).
- **Titles** (cap, every title in nine BedWars captures, `research/bedwars/titles_sounds.py`): each is
  written up with its feature. The countdown digits (fade 0/20/20), `YOU DIED!` over the respawn count
  (0/30/0), `RESPAWNED!` (5/30/5), `TRAP TRIGGERED!`, `ALARM!!!`, `BED DESTROYED!` over `You will no longer
  respawn!` or `All beds have been destroyed!` (5/35/10), `Sudden Death` (10/30/10), and `VICTORY!` and
  `GAME OVER!` (0/100/0). `You are AFK` over `Move around to return to the lobby.` belongs to the lobby.
- **Sounds** (cap, the same nine captures): the game's own are written up with their features: `note.hat`
  (countdown), `note.pling` (purchases, the alarm, the AFK warning), `mob.endermen.portal` (refusals, traps),
  `random.orb` (kills), `random.chestclosed` (deposits), `mob.wither.death` (a lost bed, Bed Gone),
  `mob.enderdragon.growl` (a broken bed, dragons), `mob.chicken.plop` (bridge egg, pop-up tower),
  `random.wood_click` and `game.player.swim.splash` (sponge), `mob.irongolem.throw` (Dream Defender),
  `liquid.water` (Magic Milk wearing off) and `random.successful_hit` (arrow hits). Everything else is
  vanilla: steps, digging and placing, hurts, bows, explosions, eating and drinking, chests, water, mob
  noises. Cosmetics add `random.pop` at volume 1 (an animated head between Amazon's shopkeepers) and
  fireworks, and some sounds come with an empty name, which a 1.8 client plays as nothing.

## 11. Stats and progression

- Stat keys: `<modePrefix><stat>_bedwars` for games_played, wins, losses, kills, deaths, final_kills,
  final_deaths, beds_broken, beds_lost, items_purchased, permanent_items_purchased, and resources
  collected per type; `winstreak` per mode. Kills and deaths also split by cause (entity_attack, void,
  fall, projectile, magic, entity_explosion, fire, fire_tick, suffocation), matching Bukkit's damage-cause
  names. No blocks-placed or time-played counters.
- **Level**: within each 100 levels the steps cost 500, 1,000, 2,000 and 3,500 XP, then 5,000 each: 487,000
  XP per prestige. `level = 100 * floor(xp / 487000)` plus the step count into the remainder; this
  reproduces the stored level in 15 of 16 real players.
- **Prestiges**: 101 named color schemes to level 10,000 (Stone, Iron, Gold, Diamond, ... Prestigious).
  Star glyphs change at 1000/2000/3000/4000 per the current parsers; Fandom says x100. Colors for 7
  prestiges disagree between sources.
- **Per-player settings** worth persisting: Quick Buy layout, Hotbar Manager slots.
- The first pass keeps the stats alone (decision 6): no XP, levels, prestiges, achievements, quests,
  cosmetics, Slumber Hotel or tokens shop.

## 12. What we have captured already

| Capture | Settles |
|---|---|
| 2026-08-20 Solo x3 | pregame and in-game sidebars, team letters and colors, Diamond II at 6:00 and Emerald II at 12:00, countdown and cancel lines, join and quit lines, start banner, purchase and not-enough lines, deposit lines, death, void and final-kill lines, bed destruction and elimination lines, the ender chest dump, respawn countdown, invincibility loss, kill loot lines, the tier-up line, a trap set-off, the team upgrade broadcast, reward lines, the end summary |
| hypixelbf (Bed Rush Duel) | titles and fade timings for countdown, death, respawn, bed destroyed, victory, defeat and AFK; the bed-clock board; the countdown, bed and kill sounds |
| hpxlbw | damage and armor values in a BedWars game, with enchanted armor |
| 2026-09-24 Solo, Lucky Rush, no modifiers (`capture-bedwars.js`) | purchases and refusals with their sounds, the affordable and owned looks, the upgrades window from scratch, a trap going off, Emerald II and III, 327 block placements and the zones they hit |
| 2026-09-24 Solo, Lucky Rush and Deadwood, no modifiers (`capture-bedwars.js`) | the forge at every level (periods, caps, the start boost), the carry limit, diamond intervals at all three tiers, Emerald II, generator caps and despawn |
| 2026-09-24 Solo, Deadwood (`capture-bedwars.js`) | every shop window at Solo prices, the upgrades window (all bought), the Tracker Shop and Hotbar Manager, the shopkeepers' name tags, Red's leather color, that Hypixel sends advancing world time stamps, and how generator items spawn. It was a private game with the host's modifiers on, so its generator timings and caps are not Hypixel's defaults |
| 2026-09-25 Solo, Waterfall, a private game with Max Team Upgrades on (`capture-bedwars.js`) | the Reveal and Counter-Offensive traps going off, Magic Milk's length, shop refusals, the void kill, the kill credit window, respawn invincibility, Bed Gone, Sudden Death and its dragons, the win, the tab text, water flow, the height limit, Emerald III and the forge's emerald cap. Its forges were maxed by the modifier, so their rates are not the defaults |
| 2026-09-25 Solo, Babylon, Lightstone and Amazon, the first two with Max Team Upgrades on (`capture-bedwars.js`) | Magic Milk against a Miner Fatigue trap, breaking a bed and losing yours, a final death and `GAME OVER!`, Amazon's slow forge again |
| 2026-09-25 Solo, Glacier and Bio-Hazard, with `Event Times: 4x - Fastest` (`capture-bedwars.js`) | the pools against forge speed, forge upgrades on Bio-Hazard, Bed Gone, Sudden Death and Game End with nobody dead, the deposit minimum, the AFK warning |
| 2026-09-26 Solo on Ashfire, Amazon, Ambush and Babylon, private games with `Respawn Time: 1 Second`, Disable Block Protection and Max Team Upgrades (`capture-bedwars.js`) | the generator cube, four base regions, the shopkeeper columns, breaks refused in zones, the floor line |
| 2026-09-26 Solo on the 22 other maps in rotation, the same modifiers (`capture-bedwars.js`) | every base box, the forge cube and shopkeeper columns on each, a bucket judged by the block clicked |
| 2026-09-26 Solo on 11 of those maps again, the same modifiers (`capture-bedwars.js`) | the base box sides the first pass left open, a second base on five maps, one of them flipped |
| 2026-09-26 Solo on Scorched Sands, Crogorm and Rooted, a walk to the middle (`capture-bedwars.js`) | the mid generators' holograms, where the world files had them missing or wrong |
| 2026-09-26 Solo on Ironclad and Sky Rise, `Respawn Time: 1 Second` only (`capture-bedwars.js`) | their base boxes, the last two maps in rotation without one, and Ironclad's second base |
| 2026-09-26 Solo on Gateway, `Respawn Time: 1 Second` only (`capture-bedwars.js`) | the forge at every level, bought in turn, as on Sky Rise and Ironclad; Gateway's Gray base, a flipped copy whose shopkeepers trade places |
| 2026-09-26 Solo on every map in rotation and Doubles on Crogorm, `Respawn Time: 1 Second` only (`capture-bedwars.js` with its forge check) | every forge's spawner count, boost and no-upgrade timing and caps; every level on Orchestra, Amazon and Sky Rise; the Emerald Forge emerald cap; six more bases; Crogorm seating its teams by mode |
| 2026-09-27 3s on every map in rotation, `Respawn Time: 1 Second` only (`capture-bedwars.js` with its forge check) | every 3s forge's spawners and no-upgrade caps; every level on Aquarium, whose Emerald Forge emeralds outlast any cap; 32 more bases; Jurassic's and Temple's three-point forges |
| 2026-09-27 4s on Terminal, Aquarium, Archway, Artemis, Fort Doon and Invasion, `Respawn Time: 1 Second` only (`capture-bedwars.js` with its forge check) | the 4s forge, each map's 3s one with 5 spawners; Terminal's generators, two emerald ones stacked over the middle; three more bases |
| 2026-09-27 4s on every four-team map in rotation, `Disable Block Protection` and `Max Team Upgrades` (`capture-bedwars.js` with its zone check) | every four-team base box; the diamond and emerald caps at every generator, Catalyst's in a second game too; Lasagne's, Shipment's and Tengshe's build limits; the radius a circle; Fort Doon's floor; Molten Forge's emerald cap |
| 2026-09-27 4s on Jurassic and Temple, `Respawn Time: 1 Second` only (`capture-bedwars.js` with its forge check) | their three-point forges in 4s: the two extra spawners drop at the gold point |
| 2026-09-27 Solo on Sky Rise and Doubles on Serenity and Deadwood, private games with `Respawn Time: 1 Second`, `Event Times: 4x - Fastest` and Disable Block Protection, the first two also Max Team Upgrades (`capture-bedwars.js`) | adding to Quick Buy, the compass menu in Solo and Doubles, every Quick Communications message and its compass line, the locked tracker, the Hotbar Manager's cursor, the upgrades window reopening and `Clicking too fast!` closing it, the queued trap's buyer, the compass's inventory slot |
| 2026-09-27 Solo on Sky Rise, a private game with `Respawn Time: 1 Second` and `Event Times: 4x - Fastest` (`capture-bedwars.js`, logging the compass target) | the tracker bought after the last enemy bed broke: its refusal and purchase lines, the readout above the hotbar and the compass following the tracked player |
| 2026-09-27 Doubles on Mirage, Sanctum and Serenity, private games with `Respawn Time: 1 Second`, `Event Times: 4x - Fastest`, Disable Block Protection and Max Team Upgrades (`capture-bedwars.js`) | the three maps' emerald generators, the recorded maps' last gap |
| 2026-09-27 public Solo on Deadwood, and public Doubles on Rooftop with a party of two (`capture-bedwars.js`) | the public pregame: strangers' names hidden, the countdown one short of full, the count and its titles, the boss bar, a party kept on one team |
| 2026-09-25 Solo on Babylon, a private game with `Respawn Time: 1 Second` and Max Team Upgrades (`capture-bedwars.js`) | the Dream Defender's aggro range on open ground, three more bedbugs |
| 2026-09-25 4s on Holmgang, a private game with `Respawn Time: 1 Second` (`capture-bedwars.js`) | every 3s/4s shop tab, the week's rotating items, `Clicking too fast!`, bedbug aggro, a Dream Defender shut in a pen |
| 2026-09-25 3s on Lasagne and 4s on Relic, private games with `Respawn Time: 1 Second` (`capture-bedwars.js`) | the four-team forge at every level, mid generators in 4s, the 3s/4s upgrades and Quick Buy prices, the rotating Sharpness II, the four-team board, the tool deposit refusal |
| 2026-09-25 Solo, Deadwood twice, private games with `Respawn Time: 1 Second`, the first also with Max Team Upgrades (`capture-bedwars.js`) | Dream Defender and bedbug damage against every armor tier and Protection level, the Reinforced Armor helmet bug, effect re-sends |
| 2026-09-25 Doubles, Ironclad, a private game with `Respawn Time: 1 Second` and Max Team Upgrades (`capture-bedwars.js`) | the golden apple, the fireball's cooldown line, a trap bought with an intruder inside, the Heal Pool's 30 s re-send |
| 2026-09-25 Solo, Sky Rise, a private game with `Respawn Time: 1 Second` and Max Team Upgrades (`capture-bedwars.js`) | bedbugs and Dream Defenders, ours and an enemy's; invisibility from both sides; the second dragons at 45:00; the bridge egg cooldown; the rejoin respawn; the bucket, deposit and height refusals; Speed II and Jump V. Its 1 s respawns are the modifier's |

Explosions, the BedWars fireball and the pearl landing were captured earlier and live in Polyp's `hypixel`
preset. Nothing yet covers other modes and pools, most potion effects or most sounds.

### 12.1 A string catalog that matches the wire

Swofty's recreation ships `configuration/i18n/en_US/official/bedwars.properties` (4,279 lines, MiniMessage
tags). Every one of the 14 lines we can check against our captures matches, colors included: the respawn
countdown, `YOU DIED!`, `You have respawned!`, `RESPAWNED!`, `BED DESTROYED!`, `You will no longer respawn!`,
the tier-up line, the invincibility loss, both not-enough lines, both purchase lines, `VICTORY!`,
`TEAM ELIMINATED > ...`, `You have been eliminated!` and `You've already purchased this item!`. Its lines for
events we have not captured are therefore strong candidates, marked as such above. A capture still decides
colors, order and whether a line is chat or a title.

## 13. Capture plan

The existing tools cover part of the CAPTURE list. The current `capture-scoreboard.js` already logs chat,
titles, the sidebar, teams and `named_sound_effect`; the 2026-08-20 session predates its title and sound
support, which is why that capture has neither. `capture-legacy.js` logs entity spawns, metadata,
equipment, velocity and explosions; `capture-explosion-blocks.js` logs block changes. Nothing logs
windows, pickups or potion effects.

**`capture-bedwars.js` and `run-bedwars.bat`**, built 2026-09-24, generator tracking added 2026-09-25 (the
client connects to `localhost:25582`, reusing the `profiles/scoreboard` login; `--selftest` runs with no
network). One tool because a BedWars game spreads the interesting events over 10 to 50 minutes and one proxy
session can only run one tool. It is the scoreboard tool, whose records it keeps byte for byte plus a
trailing `ms` line, and:

| Packets | Answers |
|---|---|
| `spawn_entity` (items), `entity_metadata`, `entity_destroy`, `collect` | each generator's schedule, items per spawn, caps, pickups |
| your `block_dig` and `window_click` drops, other players' positions | which items were generated and which were dropped, so a cap counts generated items only |
| `window_items` and `set_slot` for your own inventory | the carry limit, and the forge split (your count rising with no pickup of yours) |
| `update_time` | a server tick clock: Spigot stamps the world age every 20 ticks, so intervals stay in server ticks when the server lags |
| `spawn_entity` / `spawn_entity_living` armor stands and villagers, `entity_metadata` custom names | holograms (text, offsets, update cadence, countdown restarts), shopkeepers, dragons, golems, silverfish |
| `open_window`, `window_items`, `set_slot` with full NBT | both shop windows, lore, colors, the Quick Buy default, owned and max-tier displays, HideFlags and Unbreakable |
| `entity_equipment` per viewer | invisibility hiding armor, team leather RGB |
| `entity_effect`, `remove_entity_effect` | trap effects and amplifiers, Heal Pool, Haste, potions, Magic Milk |
| your `block_place`, answered by `block_change` | the build radius's shape, the height limits, protected areas around generators |
| `explosion`, `block_change`, `multi_block_change` | pop-up tower build order, bridge egg cadence, stacked TNT, beds under a blast |
| `world_border`, `experience`, `player_info` | 4v4 border, the level bar, tab list |
| `playerlist_header`, the list and below-name objectives | the tab's header and footer whenever they change, which objective the tab shows |
| your position under the map's minimum build height, every packet, and your ping | the void death height |

SUMMARY on disconnect or Ctrl+C: per generator and resource, the spawn count, the first spawn after the
start, intervals in server ticks with each run and the forge or tier line it follows, items per spawn, where
spawning stopped and what lay there, and how it resumed after a pickup; each generator's countdown and tier
lines; the carry limit; the forge split by distance; placements against the map file's radius and height;
each named armor stand with its names and update cadence; one dump per window title with lore, enchants,
HideFlags, Unbreakable and leather color; effects by amplifier and duration; sounds beside the nearest chat or
title line. Each game also appends a line to `captures/generators.jsonl` as you leave its world (before
2026-09-26, only at the disconnect, so a crash lost the connection's games), and `node capture-bedwars.js
--table` prints every game so far, one block per map and mode. A record's `period` is the median of its
gaps up to half again the low ones: a wait at the cap or a missed spawn stretches a gap to two periods or
more, packets land a few ticks early or late, and a forge's first wave can come early. The plain median read
Orchestra's Golden and Molten Forge iron as 41.8 and 31.5 where the spawns ran 21, and the lowest shared gap
read Terminal's 4s boost as 27 where it ran 31 (2026-09-27); both records were corrected from their own
spawns (the tables before are `generators.jsonl.before-orchestra-period-fix` and
`generators.jsonl.before-terminal-boost-fix`). A botched first Temple 4s game is out of the table
(`generators.jsonl.before-temple-retry-drop`).

1.8 wire facts the tool had to get right, verified in MCP-919 and paper_source_1.8.8: armor stands spawn as
object 78 through `spawn_entity`, not `spawn_entity_living`; the effect packet's particle flag means shown,
not hidden; world border radius fields are diameters; a fireball's spawn velocity is its acceleration; a
thrown item leaves the eye 0.3 low at the thrower's x and z (`EntityHuman.a(ItemStack, boolean, boolean)`); a
placement against a block is answered with the block at the target either way (`PlayerConnection`); the time
packet goes out every 20 ticks carrying the world age (`MinecraftServer`).

### 13.1 Generators

Every game played through the tool is a generator sample. Generators spawn whether or not anyone picks up,
the diamond and emerald holograms count down to anyone in range, and the map and mode come off the pregame
board, so normal play fills the table map by map. The sessions below cover what normal play cannot show.
They keep the tool's checklist numbers, and run in private games with every modifier off, since a modifier
changes the very numbers being measured. Status 2026-09-26: 1 and 2b are settled on every Solo/Doubles map
in rotation, each forge read to its no-upgrade caps and five bought through Molten Forge (5.1); 2 is settled
on the maps in 5.2, whose timers are the same everywhere; 2c on 25 maps (section 4).

1. **Start boost and first spawns** (per map and mode). From `Protect your bed`, leave your forge alone: the
   boost's speed and the first iron and gold show in its spawns, the first fill stops at its cap (64 iron so
   far), and gold, well under its own cap, still marks exactly when the boost ends. Then let gold fill to its
   no-upgrade cap while the boost's iron sits over iron's, and only then pick up everything, so iron refills
   to its cap: quicker than emptying the forge at once (our play, 2026-09-27). The tool says when. Where a
   diamond generator is within about 45 blocks of your island, its first spawn shows too.
2. **Mid generators**: at the start, head for the middle until an emerald generator's countdown is in sight;
   its first step down dates the next spawn even if you never see one. Stay near a diamond generator across
   6:00 and 18:00 and an emerald one across 12:00 and 24:00, and leave one untouched until it stops, for its
   cap. A countdown that restarts with no spawn means the timer runs while capped.
   - **2b. Forge upgrades, then the carry limit** (all four levels cost 20 diamonds in Solo/Doubles, 40 in
     3s/4s): at your forge, buy the levels one at a time and leave each alone until it stops, for each
     level's speed and cap and the first emerald after Emerald Forge and Molten Forge. The forge need not be
     emptied first: the spawns up to the raised cap give the level's periods and its cap (Gateway,
     2026-09-26, on a full forge: Iron and Golden Forge read 8 to 11 s after buying, Emerald Forge at its
     second emerald). Every cap logged so far rose 8 iron and 2 gold a level, which is what those forges did,
     not a rule: a level that stops before its periods show, or never starts (the boost's first fill of 64
     iron over no upgrade's 48), has the tool say to pick some up, and a stop off the logged cap says so.
     With every level bought, stand in the forge and pick up until you can't, for iron and for gold.
     The tool walks you through it in chat, holding your forge to the ones in the table: what it measured in
     gray, what to do next in yellow (let it fill, pick some up, buy the next level, leave), a finished forge
     in green, and the wait where the table has the numbers. A level is done once all its caps are in (iron
     and gold, and emeralds from Emerald Forge on). A forge with the spawner count and no-upgrade periods of one logged
     through Molten Forge is taken to need no upgrade bought, an inference from the maps logged; its
     no-upgrade caps are still read on every map.
   - **2c. Build limits**: from the middle, place blocks outward along an axis until refused, then along a
     diagonal, then up to the height limit; around a generator, place outward until the refusals stop.
     The tool's zone check calls each side in chat once a block refused with the zone's line and one that
     went in sit side by side across it (a refusal with no line can be a player in the way), then the base
     box done, a side the build floor or height limit closes included. Then a diamond and an emerald
     generator full: a countdown restart watched in view with nothing dropped since its step before (the
     countdown redraws once a second, so a restart shows up to a second after its drop), a four-team
     emerald from Emerald II. On a map the file lacks it asks for the height, floor and radius too, then
     says to leave for the next map. It gives the radius as the number between the farthest block placed
     and the nearest refused, measured flat from 0,0.

Reading the summary: each generator is split into stretches (the start boost, each forge level your team
bought, each tier), and each resource in a stretch gets one line:
- `first +Nt`: its first spawn, N ticks after the stretch began (the start, the boost's end at 0:30, the
  purchase line, the tier line). `first seen` means you were over 48 blocks away, so it may have come earlier.
- `64 in 22 spawns (per spawn 3 x21, 1 x1)`: items and spawns; several at once means spawners firing
  together.
- `2.77/s, 3 per 21.7t`: the rate over the stretches where it kept spawning, and the period it implies for
  the forge's spawners (three for iron, one for the rest). `every 201t` when there were too few spawns for a
  rate. A stretch spent at its cap is paced by items leaving, not by the forge.
- `stopped at 56 lying, most before a spawn 55`: spawning stopped with 56 generated items lying there, and
  the most it still spawned with was 55: the cap. Stops count only while you were within 24 blocks.
- `first step 21 s at 0:10: the next spawn due +613t, came +613t`: the countdown's first step down, and the
  spawn it foretold.

### 13.2 Everything else

3. **Shop sweep**: the Solo windows, the upgrades window from scratch and the refusals are captured
   (2026-09-24, 2026-09-25), and every 3s/4s tab with the upgrades (2026-09-25). Quick Buy editing, the
   compass menu and Quick Communications followed (2026-09-27), then the tracker's purchase, readout and
   compass target. Where a quick-comms message points the compass is uncaptured.
4. **With a party**: the forge split, the Heal Pool, every trap and Magic Milk are settled (2026-09-25).
5. **Deaths**: the respawn, respawn invincibility, the void kill, the credit window and a final death are
   captured (2026-09-25). AFK is not being tested.
6. **Beds**: captured (2026-09-25).
7. **Utilities**: the bridge egg, pop-up tower, sponge, bedbug, Dream Defender and golden apple are settled
   (2026-09-25). TNT and fireball physics are already measured, and TNT never breaks beds (our play).
8. **Late game**: Bed Gone, Sudden Death, the second dragons and Game End are captured (2026-09-25).
9. **Modern client** (optional): invisibility particles, bed colors, the dragon boss bar. There is no
   modern-protocol capture proxy for Hypixel yet; the existing tools are 1.8.9.

We will use some of Hypixel's maps, so the captures record each map's own numbers (forge speed, generators
and their caps, build limits) alongside the rules.

## 14. Prior art

Two full BedWars codebases sit in `Documents/GitHub`. Neither license lets us take code into an MIT
server, and neither matters for that: both are useful for what they get wrong as much as for what they
get right. The findings below come from reading the code; nothing was built or run.

### 14.1 Swofty's Minestom recreation

`HypixelSkyBlock` (the user's fork of Swofty's project, AGPL-3.0, last commit 2026-06-22):
`type.bedwarsgame` (139 files), `type.bedwarslobby`, `type.bedwarsconfigurator`, and a shared game layer
in `type.game`. Minestom `2026.06.05-26.1.2`.

- **Solid**: the shared lifecycle (waiting, a 30 s countdown, in progress, ending, terminated) with join,
  leave, rejoin and win events, where the mode supplies a handful of predicates. A small typed map schema:
  per team a spawn, bed feet and head, a forge point and both shop points; globally a waiting point, a
  spectator point, generator lists and bounds. The configurator finds most points itself (team by wool
  majority, forge by its slab platform, generators by their stair rings).
- **Broken** (read, not run): the timeline drifts (Diamond III fires at 24:00, Game End at 136:00) because
  "time to the next phase" is mutable state; tier-ups relabel holograms without changing rates; Sudden
  Death does nothing; the killer never receives resources because the inventory is cleared before it is
  counted; invisibility doubles as the spectator flag, so an invisible player cannot build or pick up;
  buying an ender pearl replaces the wooden sword; blast-proof glass is plain glass. Bedbug, Dream
  Defender, sponge, Magic Milk, armor hiding and the forge split are missing.
- **No 1.8 awareness**: Via lives only in their proxy plugin and is off by default, the game never checks
  a client's version, and generator holograms are text and block displays a 1.8 client cannot render.
- **Constants that belong in map data**: build height 105, a no-build radius of 6 around spawns, the void
  at y 0, a trap radius of 10 from the bed, chest ownership within 10 of a spawn.
- **Their numbers are not Hypixel's**: a slow forge gives 2 iron every 3 s and 1 gold every 8 s, forge
  levels multiply by 1.5, 2, 3 and 3 and add an emerald a minute, and iron and gold have no cap.
- **A string catalog**: `configuration/i18n/en_US/official/bedwars.properties`, 4,279 lines that look
  copied from the live game. Useful to cross-check wording; our captures win where they differ.

### 14.2 ScreamingSandals BedWars

`BedWars` (a Bukkit plugin, LGPL-3.0, master `1882bc8`, 2026-07-04; most servers still run the older
`ver/0.2.x`).

- **Lifecycle**: waiting, running, celebrating, rebuilding, disabled, driven by one 1 Hz task per arena,
  with the phases fixed in code.
- **About 150 option keys in 12 groups** (lifecycle and lobby, combat and respawn, building and
  protection, target blocks, spawners, shop and economy, special items, scoreboard and titles, sounds,
  statistics and rewards, server integration). They resolve arena, then variant, then global. The list is
  a ready checklist of the rule surface a BedWars mode ends up with; the raw report tabulates every key.
- **Spawners**: an interval in seconds or ticks, fractional items per spawn (the remainder is a chance of
  one more), a cap on each spawner's live items, no merging, no pickup delay, no sharing.
- **Shop**: the default is the old bronze, iron and gold economy. A work-in-progress variant approximates
  Hypixel (iron every 2 s, gold 8 s, diamonds 30 s, emeralds 60 s) without naming it.
- **16 special items**, each with its numbers in the raw report (rescue platform, protection wall,
  tracker, golem, arrow blocker, warp powder, magnet shoes, throwable fireball, TNT sheep, auto-igniting
  TNT, bridge egg, pop-up tower, and more).
- **Missing for a Hypixel-style mode**: timed generator tiers, resources to the killer, the forge split,
  permanent tiered gear, rising upgrade prices, a trap queue, a respawn kit, a reconnect window.
- **19 defects found by reading**, several of them regressions on master: respawn protection ending about
  5 ticks after a respawn, below-name health never starting, upgrades skipping items bought afterwards,
  no reward for eliminating a team's last player.

### 14.2a The recreation on Polyp

Swofty's PR 856 (2026-09) moves the recreation onto Polyp 0.3.0 and rewrites its replay and Bed Wars cleanup
(the ender chest dump of issue 788, kill tracking, hidden names, generator splitting, pearls, a stat tracker).
Reference only, GPL: its feature list is a checklist, never a source.

### 14.3 Lessons we keep

1. **The respawn right is a target** with one question, whether it is still valid: a bed, a countdown, a
   block that expires, or nothing. A final death is "invalid at death", and the 30:00 bed destruction is
   the expiring kind. Bedfight's `Beds` plus Grotto's `LastStanding` already have this shape.
2. **One game clock.** Every timeline event is a function of elapsed time, never a countdown someone
   decrements. Grotto's alarms already work this way; bedfight's bed alarm is the pattern.
3. **Team state stays on the team** and is consulted where it matters (a hit reads Sharpened Swords, a
   blow reads Reinforced Armor, a respawn and a purchase read the gear tier), never copied into
   inventories. Both codebases broke this and both drift on rejoin and late purchases.
4. **Participant state is explicit**: alive, respawning, spectating, eliminated. Grotto's `Respawns`,
   `LastStanding` and `Spectating` already carry it; no entity flag doubles for it.
5. **One placed-versus-map predicate** drives breaking, explosions, liquids, fire and rollback, keyed by
   position. Blocks the game places for a player (bridge egg, pop-up tower, sponge) carry the same mark.
6. **Typed rules, one owner per id, units typed at the boundary**, and a test that every key has a reader.
   Our `owner:setting` rules and Polyp's generated knobs already are this.
7. **An item carries only its identity.** A fireball's power, a bedbug's lifetime and a pop-up tower's
   shape resolve from the active mode when the item is used, the way Polyp profiles already resolve a
   projectile's numbers, never from parameters baked into item data. Noted 2026-09-28: a fire charge thrown
   as a fireball and TNT lit on placement are not the materials' vanilla uses but a custom item's, one any
   mode may hand out; the `hypixel` and `mmc18` mechanics (knockback, breaking, the numbers) stay as fitted,
   and what belongs to the item kind is the use itself. Today that use is a world knob
   (`TntConfig.igniteOnPlace`, the presets' `FIRE_CHARGE` projectile type); as kinds, a Hypixel TNT and a
   MineMen TNT would carry their own fuses, and a fireball its variants, the identity being the kind. Done
   2026-09-28: `ItemKind` (a `polyp:kind` stamp), `TntConfig.kinds`, a projectile variant registered under
   the kind's key, and the presets' `KIND`/`FIREBALL` constants; a stamped item is its kind in any world whose
   config knows it, an unstamped one the world's.
8. **Game logic stays on the tick thread** (Swofty's move listener runs void and trap checks on a new
   virtual thread per event).
9. **Text lives in one keyed place**, one look per network, as bedfight's `Banners` already does.
10. **Team membership outlives the connection**, which Tennacle's reclaim window already gives.

## 15. Mapping onto our stack

Bed fight (`tennacle.games.bedfight`) is the nearest mode, and most of BedWars starts from it. Paths below
are relative to each repo's main source root.

### 15.1 What carries over

- **Teams**: the roster, liveries and friendly fire are already N-team. Teams are the arena's
  `grotto:spawn` groups in ordinal order; a `Tint` gives each team its chat color, leather dye and block
  hue, and every 16-hue family works (wool, terracotta, stained glass). Parties seat side i on team i, and
  `Game.join(player, null)` fills the emptiest team.
- **Beds**: bed fight's `Beds`, `Rules` and `Banners` already do per-team bed regions, the own-bed rule
  (`beds.break`), both halves together, the losing side's titles, final kills with the note, TEAM
  ELIMINATED, and the scheduled destruction alarm. Grotto's `LastStanding` decides by team for any number
  of teams.
- **Respawns**: Grotto's `Respawns` has the delay, the ghost wait, a per-team landing, clearing a
  built-over spawn, and protection that a swing forfeits, which is Hypixel's "attacking ends your
  invincibility". Rejoins are priced by `combat.respawn.rejoin`.
- **Void and credit**: `Limits` callbacks under a plane and `KillCredit` with a tick window.
- **Explosives**: Polyp's `Hypixel.bedwars()` profile (section 6.3). What remains is game logic.
- **Enchant math**: Sharpness (1.8 adds 1.25 a level), Protection (1.8 EPF), Efficiency, all read off the
  item on every calculation, so a stamped item needs no registration.
- **Effects**: speed, jump boost, regeneration, slowness, blindness, invisibility, haste and mining
  fatigue are in Polyp's catalog; kits carry effects; Grotto `Regions` gives enter and exit callbacks for
  traps and the Heal Pool.
- **Kits and refit**: seat-derived kit items, and `Kit.refit` re-applies the kit and clears every slot the
  kit does not fill. That is exactly the BedWars loss on death once armor and tool tiers are seat state.
- **Menus**: Canvas `Screen` (per-viewer titles and items, buttons, repaint in place, the 1.8 ghost-item
  resync). `PlayerPrefs` already persists per-player kit layouts, the shape Quick Buy and the Hotbar
  Manager take.
- **Building**: Tennacle's `games/map/Building` (build region, height, floor, placed-only breaking, a
  per-game placed set, `Space.reserved`) and refusal lines through Grotto's `Refusals`.
- **Item entities**: Polyp's `DroppedItemEntity.spawn(world, pos, velocity, stack, physics, pickupDelay)`
  is documented for generators; `ItemSpawnEvent` (`Cause.SERVER`) is cancellable; `ItemPickup` refuses the
  dead; `MechanicsWorld.forEachMemberWithin` finds who stands at a forge.
- **Containers**: Polyp's `ContainerKey` has `block` and `viewer`, the latter being the per-player ender
  chest; a team key for the team chest is one registration away.
- **Stats**: Grotto `Stats` flushes slot counters to career vault keys; Tennacle has `FileVault` and
  `MatchLog`.
- **Maps**: Barnacle's point and pos markers are documented for generators, shops and NPCs, and
  Barnacle's DESIGN already names `bedwars:generator` and `bedwars:shop`. Bloom's symmetry works in quarter
  turns and mirrors, which fits 4- and 8-way maps.
- **Replay**: item entities, TNT, mobs and NPCs, every block write (explosions included), container
  slots, chat, titles and the sidebar all record today.
- **Replay start** (the user, 2026-09-28): the waiting room is no part of a game. Ours: a Bed Wars tape starts
  as the seats open (`Staging.onOpened`), the lineup stamped whole; a mode with no room records from the launch.
- **Holograms on 1.8**: the proxy's ViaBackwards has `map-display-entities: true`, so a modern text
  display reaches a 1.8 client as armor stands.

### 15.2 What BedWars would trip over on day one

Each of these was read in the code, not just reported:

1. **Empty teams abort the match.** `LastStanding.requireAllSeated` decides `aborted` when any team has
   nobody present or reserved at GO, and an 8-team lobby starts with empty teams. They should start out,
   bed gone, with a minimum team count. Done 2026-09-27: `LastStanding.requireSeatedAt(phase, teams)` starts
   them out as `NEVER_ARRIVED`, and bed fight's `Beds` takes their beds away without a fall. A last-standing
   game never plays with fewer than two teams, whatever its minimum: one team has nobody to fight (the
   user's rule). The fix found `Phases.onEnter` keeping only the last action a phase was given, which had
   switched the seated check off in bed fight and in bridge under minemen.
2. **Every TNT placement would print `You can't place blocks here!`.** Polyp primes TNT by cancelling the
   placement (`ExplosionSystem`), and `Refusals` reads any cancelled placement not marked `handled` as a
   refusal.
3. **Knockback-only kills earn no credit.** `KillCredit` records only hits that dealt more than 0 and came
   from a player. Snowballs, eggs and the BedWars fireball's direct hit deal 0, and blast damage names the
   TNT or fireball entity as its source, so a fireball knock-off into the void credits nobody. It needs
   recording off knockback and resolving projectile to shooter and TNT to igniter.
4. **Explosions break nothing in a match.** `Matches.fold` clears `blockBreaking` for every match and
   expects a mode to name a registered `BlockBreaking`; none is registered with `FieldFns` yet. A
   placed-only rule also needs the game's placed set, which is private to the handle `Building` gives bed
   fight's `Rules`. Explosion breaks write blocks directly, so `Beds` never hears one; placed-only keeps
   beds safe by construction.
5. **The death spill never runs in a match.** Bed fight cancels the killing blow and handles the death
   itself. Resources to the killer is mode code at the death decision, not a `Spill` kind, which keeps the
   "minigames never drop" fold intact.
6. **Pink and Gray print wrong.** `Palette.name` takes the display color's name, so they print "Light
   Purple" and "Dark Gray" with letters L and D; the capture shows `P Pink` and `S Gray`. The default
   ordinals also lead with blue and end with dark purple and gold, not R B G Y A W P S. Done 2026-09-27:
   `Palette` prints light purple as PINK and dark gray as GRAY with letter S, from a table of BedWars'
   names. The BedWars arenas author each team's color as its livery base, in Hypixel's order from 1 Red
   to 8 Gray, so their names, armor and wool follow the captured color and never the ordinal default,
   which the duels keep blue-first. They had authored only the display color, which left armor and wool
   on the ordinal default: Red's would have been blue.
7. **Two-team assumptions**: bed fight's board has two side rows, `Queues` pairs exactly two equal sides,
   `Summary` compares two sides, and the hypixel look says "won the Duel!". Done 2026-09-27, each as the
   shared thing it is: boards render a row a side per viewer (Grotto `Scoreboard.rows`); `Summary` has a
   game style over any number of sides, the winning side's name and seats over the top places by a stat
   (`hypixelGame`, `top`; Canvas `HypixelCard.winners`, `ranked`); a `Mode` declares its family, duel or
   game, and the hypixel verdict reads it (`VICTORY!` gold, `GAME OVER!` red, no subtitle); a game fills
   up on its map through `/play <mode>` (`Matches.open`, `enter`, `waiting`), a party on one team with
   room (`Roster.teamWithRoom`), while `/queue` keeps pairing duels.
8. **Marker counts are exact**, so one game type cannot serve both 4- and 8-team maps. Done 2026-09-27: a
   type can expect one marker for each team (`GameType.expectEachTeam`), however many teams the map seats,
   and the launch and the map pool read the same check; bed fight's beds use it. Team size is the rule
   `Roster.TEAM_SIZE` since 2026-09-27: a team seats at most that many, a held seat counted.
9. **Invisibility hides nothing but the body.** Polyp's `Invisibility` sets the entity flag; armor stays
   visible to everyone, and no per-viewer equipment filter exists.

### 15.3 New components

| Component | Home | Scope |
|---|---|---|
| Timeline | Tennacle | done 2026-09-27: `games.Timeline` - named events at offsets from LIVE, each a `timeline.<id>` rule re-armed when it changes, a hook per event, `watched` entries another module fires (Bed Gone stays `Beds`' clock) and `next()` for the board |
| Generators | Tennacle | done 2026-09-27: `games.Generators` - timed drops at points under a pool's cap, a restart on demand (a drop on the spot, the clock from there), a pickup copied to teammates near the item. Bed Wars' `Forges` (the level as team state, the 30 s boost, spawner counts from the iron marker's payload) and `Mid` (tiers the timeline raises) ride it. Open: the carry limit (512 iron, 64 gold) needs a Polyp pickup hook; a level-up restarts a side's spawners together rather than the captured stagger; the four-team Emerald Forge cap is taken as 7 |
| Holograms | Canvas | done 2026-09-28: `canvas.hologram.Hologram`, a line a marker armor stand at the captured text height, spawned through the app (Tennacle tags it inert); Bed Wars' keepers take their lines through `Keepers.at(..., labels)` and `Mid` shows each generator's tier, name, countdown and turning block. Per-viewer text is not built: nothing captured needs it |
| Shop screens | Canvas, Tennacle | item shop done 2026-09-28: `games.shop` (below) under Bed Wars' `ItemShop`, `QuickBuyPicker` and `HotbarManager` screens, every captured line, lore rule and sound; Quick Buy pins in `PlayerPrefs` (`shop.Favorites`), the Hotbar Manager editing the kit layout; the upgrades screen, the Tracker Shop, the compass menu with Quick Communications and the per-player compass target the same day (6.2) |
| Currency | Tennacle | done 2026-09-28 as `games.shop`: a `Currency` held and taken (`ItemCurrency`, items in the hotbar and main inventory), a `Price` that reads `4 iron` as a rule (`shop.<offer>`), `Offer`s (`Goods` through the kit, `Rank` levels on a seat's or team's ladder), and `Shop.buy`: refused, short by N, or paid and handed over. Kept out of Polyp: none of it is a vanilla mechanic |
| NPCs | Tennacle | done 2026-09-28: `games.Keepers`, a body at each point marker, inert (Polyp `WorldPolicy.INERT`: no hit, shot, push or blast reaches it), facing its side's spawn and turning to the nearest player it sees within 8 blocks (never an invisible one, Hypixel v1.11), a right click doing the mode's use. Bed Wars' item shopkeepers are armorer villagers (Via shows a 1.8 client a blacksmith). Left: the upgrades keeper, the name lines over them |
| Team upgrades | Grotto, Tennacle | done 2026-09-28: `Upgrades`, each a `Rank` on a team ladder bought in `UpgradeShop` (the captured window, lore and lines, the team broadcast, "Clicking too fast!"); the kit stamps Sharpness, Protection and Feather Falling by the team's levels (Grotto restamps what the team holds when a level is written) and wears Haste by `Kit.effect` reads; the forge follows `Forges.LEVEL`; `HealPool` is a Grotto `Regions` ball of 21 around each spawn; the stamps reach the team chest and ender chests at the purchase (`Chests`) |
| Traps | Grotto, Tennacle | done 2026-09-28: `Traps`, a team queue of three in game state bought at 1, 2 and 4 diamonds (rules), a `Regions` ball of 12 around each standing bed, the four effects, the owners' title, line and sound, Reveal's alarm and bell, a 30 s cooldown (rule) after which the next goes off on whoever is still inside, the buyer's right-click removal; Magic Milk the same day (6.3) |
| Tiers and the death outfit | Grotto, Tennacle | tiers done 2026-09-28: Grotto's kit re-reads the state its items name (armor, the pickaxe, axe and shears as seat ladders), stamps what it hands over (unbreakable; team enchantments next), gives into a layout role's slot and takes a slot away; Bed Wars' `Gear` drops each tool a tier at a death through `Lives.onDeath`, and `Chests` spills the ender chest at the forge at a final death; `Spoils` hands the killer what `combat.death.to-killer` admits |
| Chests | Polyp, Tennacle | done 2026-09-28: Polyp's containers fire `ContainerOpenEvent` (refusable), `ContainerInsertEvent` (a click moving an item in, refusable) and write a store live (`set`, `insert`, `keyAt`); Bed Wars' `Chests` locks a side's team chest while it stands, punches the held stack in with the captured rules and lines, restamps stored items on a stamp's write and spills the ender chest at a final death |
| Structure builds | Grotto or Tennacle | done 2026-09-28 as `games.map.Structures` (the row below): one builder for every block-laying item: a build is a stream of (cell, block) placements a tick with the shared rules (air only, marked placed, build limits and protected zones, a sound per block, a push for a player in the cell). The pop-up tower feeds it a blueprint turned to the chest's facing, two cells a tick; the bridge egg the cells under its path, 5 ticks late; later builders plug in the same way |
| Special items | Tennacle, Polyp hooks | done 2026-09-28: the pop-up tower, bridge egg, sponge and creatures are `games.items`, modules any mode with building rules may list (their copy through the look's `ItemLines` cue, a side's wool through `TeamBlocks`), Bed Wars keeping only the Sudden Death wave (`Dragons`); Magic Milk and the water bucket (Polyp's fluids, not the blocks-and-stuff module). TNT and the fireball are item kinds (principle 7): Polyp `ItemKind` stamps a stack, `TntConfig.kinds` and a projectile config keyed by the kind carry its knobs, the `hypixel` and `mmc18` presets name theirs (`hypixel:tnt`, `hypixel:fireball`, `mmc18:tnt`, `mmc18:fireball`), and the shop hands out the Hypixel ones stamped |
| Mobs | Polyp | done 2026-09-28 (the Creatures row): silverfish and golem with goals, team targeting, a lifetime, damage through Polyp's attack pipeline, credit to the owner |
| Armor hiding | Polyp | a per-viewer equipment filter for invisible players |
| Knockback credit | Grotto | `KillCredit` off knockback; projectile to shooter, TNT to igniter |
| Empty-team start | Grotto | done 2026-09-27: unseated teams out at GO, two teams at least |
| Waiting room | Grotto | done 2026-09-27: `Staging` holds joins at the arena spawn, no damage, a seat left is free again; GO seats everyone, runs the seating (the kit) and takes the room's blocks down |
| Bed modes | Tennacle | done 2026-09-27: `games.beds` - `Beds` (any bed marker, any counter), `Lives` (respawn while the bed stands, the last death, leaves and rejoins, void), `Banners`, `Rows`; bed fight and Bed Wars share them |
| Placed blocks | Grotto or Tennacle | the per-game placed set as a module explosion rules can read; reserved zones; `FieldFns` factories for placed-only and blast-proof glass |
| Stats outcomes | Grotto, Tennacle | kills, deaths, final kills, final deaths, beds broken and lost, wins, losses and streaks; no XP or levels (decision 6) |
| Item skins | Grotto, Tennacle | done 2026-09-28: `Kit.Skin` and `games.Skins` (6.1), Bed Wars' wood; the pick is a dev command until the standard menus |
| Block drops | Polyp | done 2026-09-28: `BlockLoot` and `LegacyLoot` under `BlockDrops.VANILLA` / `VANILLA_18` (section 4) |
| Bridge Egg, Compact Pop-up Tower | Tennacle | done 2026-09-28: `games.map.Structures` (builds over ticks through `Building.place`), Bed Wars `BridgeEgg` and `PopupTower` (6.3) |
| Creatures | Polyp, Tennacle | done 2026-09-28: Polyp `mechanics/mobs` (one engine for both eras: 1.8's walker or 26.1's typed walker by the `pathing` knob, a swing through walls or not by `attackNeedsSight`, the presets picking; silverfish, iron golem, ender dragon on 1.8's circuit in both; every number a knob), `games.items.Creatures` (Bedbug, Dream Defender, a side's dragon) and Bed Wars' `Dragons` (the Sudden Death wave with its lines, title, growl and `More Dragons` row), `Lives.blame` and `combat.death.to-owner` (6.3). Uncaptured: the dragon's own death line, whether a bedbug's squares gray out |
| Fluids | Polyp | engine done 2026-09-28: `mechanics/fluids`, water and lava flow with every rule a knob (rate, drop-off, slope search, infinite sources, a direction gate, what blocks a flow, what it replaces, the lava mixing, updates off) and the era's structure as strategy knobs (`passage` faces between shapes, `waterlogging`, a fall spreading beside three sources, what a wash drops), the `vanilla18` and `vanilla` presets picking; the bucket goes through the place and break events Building sees. Bed Wars (2026-09-28): the sponge (6.3) and the base boxes water never runs into (`space.flow.reserved`) |
| Filling queue | Tennacle | done 2026-09-27 as `/play`: a game per mode and platform fills on its map, its `Countdown` waiting for `START_AT` seated and stopping short below it; team chat and `/shout` still to come |
| Map keys | Tennacle | written by the converter (15.4); the mode still has to declare and read them |
| Replay metadata | Archipelago | encode custom names, display text and villager data, which drop today |
| Team names | Tennacle | done 2026-09-27: Pink and Gray print as captured, and every team role follows the arena's color |
| The mode | Tennacle | started 2026-09-27: `games.bedwars` - four modes (`bedwars-solo`, `-doubles`, `-3s`, `-4s`) off one type with a team size and a start threshold each, pools under `arenas/bedwars` holding guessed maps out, the room, beds, kills and final kills, the circle and limits, the captured board and end block. Generators and the timeline the same day: the tier lines, the 25:00 warning, Bed Gone with its title and wither, the Sudden Death title, the 50:00 end by alive then final kills, beds and kills, and the board's next-event row. The item shop, team upgrades, traps, chests, the compass, tracker, quick messages, kill loot and the holograms 2026-09-28. The dragons and the special items 2026-09-28. No hidden pregame names or `has joined` lines yet |

### 15.4 Hypixel's maps as arenas

The map archive (`Desktop/Hypixel Map Archive/!Hypixel Maps/Bedwars`) holds 67 Solo/Doubles, 60
Triples/Quads and 6 4v4 maps, plus seasonal and other modes. Each is a bare 1.8 world: region files and a
stub `level.dat`, with no spawns, generators or shopkeepers. What the worlds do give (`map_scan.py`,
`map_markers.py` in `research/bedwars/`):

- One bed per team on every map (8, 4 or 2). Lighthouse carries 4 extra; the 8 farthest out are the bases.
- Teams go round the map clockwise in Hypixel's order (R B G Y A W P S; R B G Y on four-team maps). Where a
  map colors each base with a few accent blocks running once round in team order, those colors seat its
  teams (our play). They matched the team played on 20 of 23 captured maps; on Ashfire, Babylon and Crogorm
  Hypixel's Solo sat one or two bases off them, and Crogorm's Doubles followed them (2026-09-26). Elsewhere
  the captured base fixes where the order starts.
- Every generator stands on a diamond or emerald block with open air above, usually a lone one; Carapace's
  lower emerald stands on glass. Its items drop over the middle of the block, 1.5, 2 or 2.5 above the top
  block under it by map, and its "Diamond" or "Emerald" line stands 2.97 above the drop point (95/32 on the
  1.8 wire, 88 of 88 captured). Every generator has that hologram and name line (our play). The arenas
  standardize the drop at 2.5 over the top block, on every map (decided 2026-09-27), the rise of most
  captured generators: 139 of 220 diamond and 101 of 154 emerald ones. The captures had 1.5 on Ambush,
  Artemis, Build Site, Carapace, Deadwood, Frogiton, Harvest, Harvesting, Impere, Infinite, Ironclad,
  Lasagne, Lost Temple, Relic, Rooted, Sanctum, Silver Birch, Terminal and Whiskers (emeralds on Fort Doon
  and Katsu too), 2 on Ashfire, and 2.5 elsewhere; the markers keep each captured point. A generator's turned
  copies keep one height where the captures drop them at one: one of Aquarium's four diamond generators
  stands on two blocks, yet all four drop at 83.5, and so they stay. Copies the captures set at different
  heights keep theirs (Sky Rise's and Rooted's pairs), each over its own block. Rooted's emeralds stand on stacked blocks and Scorched Sands' beside other emerald blocks.
  Terminal and Relic stack two emerald generators over their middle, 8 and 7 blocks apart, each with its
  own hologram (2026-09-27).
- The forge usually stands on a 3 x 3 slab platform (23 of 31 captured maps). The archive's Orchestra has
  none: it is an older build, and the live map is the archive's Orchestra v2, whose forges stand on slabs.
- Hypixel's pregame room, a 25 x 10 x 25 box over the middle, was in every world; since 2026-09-28 the
  conversion air-fills the `bedwars:lobby` box and the room lives once in `arenas/rooms.bncl` (a `room` region,
  group `bedwars`, cut from Acropolis by `gradlew roomExtract`: 877 blocks, the floor two layers up), laid by
  `games.map.Rooms` at the map's box before the seats open and cleared at GO, the way bridge cages come from
  their own arena, at the user's call.
- Spawn, forge and shopkeepers are not in the worlds. Our captures give them for 31 maps, and they sit the
  same way against every base's bed, forges to within half a block. A flipped copy of a base mirrors the shopkeeper spots. On 16 maps where
  both hands were captured the item shop and upgrades also trade places; on Impere they stay put. A base the
  captures saw keeps its own positions, one of the same hand copies it, and an uncaptured map's flipped
  copies trade. All 53 captured bases come out exactly.
- Items drop where the captures saw them, to the 1/32 block the 1.8 wire carries (`spawn_points.py`: a
  forge or generator drops 0.2 up and at most 0.1 across). A generator's point is its drops', else its
  hologram's less 2.97, else a turned copy of one over a block of the kind at the same rise; a map no
  capture saw gets 2.5 above the block (assumed). The drops corrected four maps' generators: Orchestra's
  diamonds stand at 57.5 out on both axes, where the archive's Orchestra has nothing and its Orchestra v2
  has the islands, so v2 is the live build and its arena plays as Orchestra (2026-09-27); Aquarium's emeralds
  drop at y 96.5, away from the blocks the search took;
  Holmgang has two more diamonds at z -11.5 and 12.5; three of Serenity's diamonds had been put over
  blocks 11 above their drop points.
- Forges are placed by hand. Most drop 0.5 over their slab (116 of 155 captured bases), and every base of
  eight maps 1.0 over it; Glacier's sit on block edges (x -86.0) and Amazon's on corners, base after base.
  A few drift: one of Waterfall's drops half a block under the rest, Jurassic's east forge half a block
  under its north one, Temple's north forge 0.31 off a turned copy of its east one, and Deadwood's (66.125
  to 66.3125) and Katsu's (76.656) sit off the half-block grid. The arenas standardize them (decided
  2026-09-27): every base of a map takes one layout against its bed, the captured one on the half-block
  grid that most bases share, then the one 0.5 over its floor, snapped to the grid where none is on it.
  That moves 10 captured forges; every other captured forge and generator point goes in as captured, and
  `research/bedwars/spawn-points-2026-09-27.json` holds every drop seen. A captured base's own forge tells
  its hand (Lucky Rush's sit 2 blocks aside, mirrored base to base), and an uncaptured base's blocks against
  the captured ones' tell its hand, however slight the difference.

  | Map | Base | Captured drop point | Arena |
  |---|---|---|---|
  | Deadwood | Red | -29.5, 66.125, -98.5 | y 66.0 |
  | Deadwood | Blue | 30.5, 66.1875, -98.5 | y 66.0 |
  | Deadwood | Green | 99.5, 66.1875, -29.5 | y 66.0 |
  | Deadwood | Yellow | 99.5, 66.3125, 30.5 | y 66.0 |
  | Deadwood | Pink | -98.5, 66.1875, 30.5 | y 66.0 |
  | Deadwood | Gray | -98.5, 66.1875, -29.5 | y 66.0 |
  | Waterfall | Gray | -76.5, 65.5, -31.5 | y 66.0 |
  | Jurassic | Blue | gold 79.5, 67.5, 10.5; iron at z 9.5, 10.5 and 11.5, y 67.5 | y 68.0 |
  | Katsu | Red | 0.5, 76.65625, -93.5 | y 76.5 |
  | Temple | Red | gold 0.5, 62.5, -73.8125; iron at x -0.5, 0.5 and 1.5, y 63.0, z -73.8125 | z -73.5 |

`gradlew bedwarsMaps -Parchive=<dir> -Pmarkers=<dir> [-Pout=<dir>] [-Pmaps=<names>]` (`tennacle.dev.BedwarsMaps`)
builds one arena per marker file: the world through Bloom's pre-1.13 reader, relit, and these markers, groups
being team ordinals in Hypixel's order:
Since 2026-09-28 the reader settles stair corners and fence, wall, pane and bar arms from the neighbors (Bloom
`Connections`), states a 1.8 world never stores and both clients work out themselves; before it the server
collided on straight stairs and a 1.8 client rubber-banded on every corner. The user's Babylon rubber band at
33.8, 81, -110.5 is the birch stair at 33, 81, -111, a west-facing stair in front of it: the pass stores it
`outer_left`, where it was `straight` (dumped from the converted arena). The markers now sit in group folders
(`Solo-Doubles Maps`, `Triples-Quads Maps`, `4v4 Maps`), 133 maps.

| Key | Shape | Group |
|---|---|---|
| `grotto:spawn` | pos, facing the bed | team |
| `bedwars:bed` | region, both blocks | team |
| `bedwars:forge` | point, where gold drops | team |
| `bedwars:forge-iron` | point, where iron drops; payload: how many of each mode's spawners drop there, keyed `solo`, `doubles`, `3v3v3v3`, `4v4v4v4` | team |
| `bedwars:shop`, `bedwars:upgrades` | point | team |
| `bedwars:base` | region, where captured | team |
| `team` | data, `base` in the team's color, which every role (name, armor, wool) falls back to | team |
| `bedwars:diamond`, `bedwars:emerald` | point, where items drop | none |
| `bedwars:radius` | data, `radius`: a block goes in while its distance from 0,0 is under it | none |
| `bedwars:height`, `bedwars:floor`, `bedwars:void` | limit y: highest and lowest placeable, the kill plane at 0 | none |
| `bedwars:lobby` | region, the pregame room to clear at the start | none |

Arenas are tagged `bedwars` and by their team count: `bedwars-solo` and `bedwars-doubles` for 8 teams,
`bedwars-3v3v3v3` and `bedwars-4v4v4v4` for 4, `bedwars-4v4` for 2. They live in `arenas/bedwars/`, where the
other modes' map pools, which read `arenas/` alone, never load them. Hypixel allows their use while we claim
no creation or rights in them (2026-09-27): each names Hypixel as its author, and the folder's README says so.

A map no capture recorded is a guess throughout (decided 2026-09-27): nothing on it is taken as right, not
even the rules that held on every map captured, and its arena carries `bedwars-guessed` so a rotation can
leave it out. Every arena's `bedwars` data holds its status and what still needs a capture. Recorded: 28
Solo/Doubles and 27 Triples/Quads maps (Tengshe is recorded but not in the archive). Guessed: 39
Solo/Doubles, 33 Triples/Quads and all 6 4v4 maps. The recorded maps need nothing more: Mirage's,
Sanctum's and Serenity's emerald generators were the last gap, captured 2026-09-27. Mirage's stand at y
50.5, 17 blocks under its bases, where their blocks had put them.

All 67 Solo/Doubles maps are converted: 28 from captures, 34 with spawn, forge and
shopkeepers guessed from the slab platform, and 5 with none found (Airshow, Casita, Casita v2, Crypt,
Slumber). So are all 60 Triples/Quads maps (2026-09-27): 27 from captures, 31 guessed, and none found on
Daolong and Obelisk, the 27 in rotation with a base box from their zone round; Tengshe, in rotation, is not
in the archive. The guesses land within 2 blocks on about 60% of captured maps, so those maps wait on a
check. Sign text, heads, banner patterns, flower pot contents and entities do not survive the conversion.

## 16. Decisions for you

1. **Which mode first.** Decided 2026-09-26: the core modes from the start, on the downloaded maps; since
   2026-09-27 Solo, Doubles, 3s and 4s, with 4v4 out for the foreseeable future, as are Dreams. Items 1, 6,
   7 and 8 of 15.2 therefore come before the first game: the empty-team start, team names, the
   filling queue, boards and summaries for any team count, and map types by team count.
2. **Parity target.** Decided 2026-09-27: the first pass is the Hypixel look alone, named `hypixel` as the
   look and the preset (captured copy, `Hypixel.bedwars()` mechanics, the numbers as rules so `/rules` shows
   them); a house look comes later. Final kill, bed break and kill lines already exist in bed fight's
   hypixel preset, and kill credit timing is in section 8.
3. **Holograms.** Decided 2026-09-27: armor stands sent natively by viewer protocol, unless Via's
   text-display emulation proves exact for 1.8, in which case Via.
4. **Where generators and the timeline live.** Decided 2026-09-27: Tennacle or Barnacle, not Grotto.
   Grotto stays general game-building tools, grown only where BedWars needs something general. Built the
   same day in Tennacle's `games` package, for any mode.
5. **One type per team size, or a capacity rule.** Decided 2026-09-27: one general type. Team size and
   generator counts are rules with each mode's defaults (1 a team in Solo, 2 in Doubles, 3 in 3s, 4 in 4s,
   each on the maps for its team count), changeable like bridge's rules and never prevented. Exact marker
   counts forced a type per map shape until a type could expect a marker for each team (15.2).
6. **What a first cut leaves out.** Decided 2026-09-27: everything but the game and its stats. The stats
   are kills, deaths, final kills, final deaths, beds broken, beds lost and the like; out are Dreams,
   rotating items, cosmetics, challenges, quests, tokens, the Slumber Hotel, XP and levels.
7. **Captures.** Solo/Doubles are done (2026-09-26), and the four-team maps in rotation (2026-09-27):
   3s forges, base boxes, mid generator caps and the build limits the file lacks; 4s forges are each map's
   3s one with 5 spawners on the ten maps played in both, and are taken to be on the rest (our play). Left:
   every guessed map, when it returns to rotation, and Tengshe, whose world the archive lacks.
   A walk past every base and generator would make the copied forge and generator points exact.
8. **Which Hypixel maps, in which modes.** Decided 2026-09-27: the first rotation is the fully captured maps
   alone, each in the modes of its team count (8-team maps in Solo and Doubles, 4-team maps in 3s and 4s).
   Guessed maps stay out, and so does Tengshe for now.

9. **Commands.** The user, 2026-09-28: every queue and Bed Wars command (`/play`, `/queue`, `/skin`) is a
   development tool (`Commands.dev`), never baked into the game; the standard commands and menus come later
   and will call the same hooks (`Matches`, `Skins`).

The "minigames never drop" rule holds for BedWars: nothing spills at death except the ender chest at a
final death, and resources move straight to the killer.

## Raw research

The seven research reports this page condenses, with every source link, the conflicts between sources and
the history behind each number, are in `Desktop/minemen-capture/research/bedwars/`: `rules-and-timeline.md`,
`item-shop.md`, `upgrades-generators.md`, `public-data.md`, `prior-art-swofty.md`,
`prior-art-screamingsandals.md` and `stack-mapping.md`, plus `capture-digest.md` for the 2026-08-20 Solo capture,
the API data the stats section was built from, and `bwmapinfo.json`, the map file behind section 4's build
limits and pools.
