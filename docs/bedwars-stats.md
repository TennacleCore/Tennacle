# Bed Wars stats

What Bed Wars counts, what each number means exactly, and where it is measured. Drafted 2026-10-19 from the
user's list. Every stat is kept for all of Bed Wars and for each mode. Levels and XP come later, worked out from
these numbers.

## How it is kept

- **Per game, per player** (not per seat: a seat can change hands). A game's sheet is built in memory while it
  runs, then written once at the end:
  - a `match_stats` row per (match, player, stat), so a game's numbers stay readable later (match pages, the API, XP)
  - the career totals added in one batch, to `bedwars:<stat>` and `bedwars-<mode>:<stat>` in the `stats` table
- **Pairs** (who damaged or killed whom) are kept per game only, in `match_pairs`, and never as career totals.
  Career totals per opponent would grow with every pair of players who ever met.
- **Units:** counts are whole numbers; damage is in tenths of a health point (a heart is 20); time is in seconds.
- **Games that count:** a win or a loss. A draw or an aborted game counts nothing, as today. Leaving a live game
  counts as a death, and as a final death once the bed is gone, as today.

## The catalog

### Results
| Stat | Means |
|---|---|
| `games` | games finished as a win or a loss |
| `wins`, `losses` | as now |
| `winstreak`, `best-winstreak` | wins in a row, overall and per mode; a loss sets it to 0 |
| `time-played` | seconds from the start until they were eliminated, left, or the game ended |

### Kills and deaths
| Stat | Means |
|---|---|
| `kills`, `deaths`, `final-kills`, `final-deaths` | as now |
| `beds-broken` | as now |
| `beds-lost` | their team's bed fell while they were seated: broken, or the bed-destruction clock |
| `<cause>-kills`, `<cause>-deaths`, `<cause>-final-kills`, `<cause>-final-deaths` | by cause, below |
| `assists` | damaged the victim within the kill-credit window but didn't get the kill |

Causes: **melee** (a hit), **bow** (arrows), **void** (fell below the map), **fall**, **fire** (fire and lava),
**explosion** (fireballs and TNT), **creature** (their silverfish or golem), **other**.
- **Void** is decided by how they died. If someone hit them first, it's that player's void kill.
- **Creature** kills go to the creature's owner, as kill credit already does.

### Resources
Kept for each of iron, gold, diamond and emerald.

| Stat | Means |
|---|---|
| `<res>-from-generators` | picked up from a forge, diamond or emerald generator (drops carry the generator's tag) |
| `<res>-from-own-forge`, `<res>-from-other-forges` | the forge share of `from-generators`: their base's, or another side's (kept against that side per game) |
| `<res>-from-own-diamond-generators`, `<res>-from-other-diamond-generators` | a diamond generator is its nearest side's, shared with the next nearest while that one is under 1.5 times as far. That gives every side one on Solo and Doubles maps and two on most Triples and Quads maps; Deposit, Lost Temple, Manor Royale, Nostalgia, Sky Festival, Terminal and Zen Plaza put one in front of each base. Another side's is kept against both its owners per game |
| `<res>-from-emerald-generators` | |
| `<res>-from-players` | picked up after another player dropped it |
| `<res>-stolen` | handed over on a kill: the victim's resources, given to the killer |
| `<res>-from-chests`, `<res>-into-chests` | taken out of chests or put in, net per visit: read off the visitor's own inventory from open to close, less what reached or left them some other way meanwhile |
| `<res>-lost-to-players` | handed to their killer on a death |
| `<res>-lost-to-void` | carried into the void on a death with no killer, or thrown off the map |
| `<res>-spent` | paid at the shops; also `<res>-spent-on-<kind>`, `<res>-spent-on-<purchase>` and `<res>-spent-for-team` (upgrades and traps) |

Only a death that hands resources to a killer counts as lost to players. Every other death loses them to the void.

### Combat
| Stat | Means |
|---|---|
| `damage-dealt`, `damage-taken` | damage after armor, to and from players. Pairs per game in `match_pairs` |
| `hits`, `hits-taken` | melee hits landed on players and taken from them |
| `hits-blocked`, `damage-blocked` | hits taken while blocking with a sword (1.8 blocking), and how much it saved |
| `arrows-shot`, `arrows-hit` | |

### Items and actions
| Stat | Means |
|---|---|
| `items-bought`, `permanent-items-bought`, `upgrades-bought`, `traps-bought` | at the shops, each purchase once by its kind: armor, tools and shears are permanent, the team's ladders are upgrades |
| `bought-<purchase>` | each thing bought, a ladder by its level: `bought-wool`, `bought-reinforced-armor-2`, `bought-its-a-trap`. Per game too, so a match says who bought the team's upgrades |
| `golden-apples-eaten`, `potions-drunk` (plus `<potion>-potions-drunk`: speed, jump, invisibility) | |
| `pearls-thrown`, `fireballs-thrown`, `tnt-placed` | |
| `silverfish-spawned`, `golems-spawned` | bedbugs and dream defenders |
| `bridge-eggs-thrown`, `popup-towers-placed`, `sponges-placed` | |

### Building
| Stat | Means |
|---|---|
| `blocks-placed`, `blocks-broken` | blocks players placed, and broke. Map blocks can't be broken, so every break is a player's block |
| `bed-defense-broken` | broken blocks that touched the bed of a team still in the game: digging into a bed's defense |
| `bed-attempts`, `bed-attempts-failed`, `bed-dig-ticks` | digging at another side's standing bed, the bed itself or a block touching it. An attempt runs from the first dig until the bed falls, or until they die or leave with it standing (failed). Dig time is in ticks, a twentieth of a second, since a bed goes in under one |
| `bed-defense-placed` | blocks placed touching their own standing bed |
| `bed-saves` | kills of a player who had dug at the killer's bed in the last 5 seconds |
| `time-guarding-bed` | seconds within 6 blocks of their own standing bed while a living player of another side was too |

### Where they spent the game
Time, in seconds, sampled once a second while they were alive and on the map.

| Stat | Means |
|---|---|
| `time-at-base` | inside their own team's base box |
| `time-at-mid` | inside the map's middle, where it has one |
| `time-at-emeralds` | within reach of an emerald generator |
| `time-at-diamonds` | within reach of a diamond generator |
| `time-at-other-bases`, split three ways | inside another team's base box, by that team's state at the time: |
| | `time-at-bases-alive` (it still has its bed) |
| | `time-at-bases-bedless` (bed gone, players left) |
| | `time-at-bases-out` (eliminated, or never filled) |

**Teams aren't sorted into friends and enemies.** Two teams may cross-team their first rush and play 2 against 2,
and a team that never filled or was knocked out is no threat. So the split is by the other team's state, not by
whether it is an "enemy". Each game also keeps the time at each team's base in `match_pairs`, so a later rule (say,
"they fought that team") can tell rushes from cross-team visits.

## Where each one is measured

| Group | Hook |
|---|---|
| kills, deaths, finals, cause | `Lives.killed`, carrying the fatal damage's type (dropped today before `Lives.Death`) |
| assists | `KillCredit`'s window of who hit the victim |
| beds | `Beds.broke` and `Anchor.onFall` |
| generator pickups | the generator tag (`Generators.made`) on `PickupItemEvent` |
| player drops, void throws | tag `PLAYER_DROP` spawns with the dropper; `OffMap` removing one is a void loss |
| stolen and lost to players | `Spoils`, at the hand-over |
| chests | the chest window's clicks, per visit |
| spent and bought | `Shop`'s `Sold` |
| combat | `DamageAppliedEvent` and `BlockingDamageEvent` (Bed Wars doesn't install `Hits` today) |
| items | `ConsumeAppliedEvent`, `ProjectileLaunchEvent`, `TntPrimeEvent`, `Creatures`, `BridgeEgg`, `PopupTower`, `Sponge` |
| building | `Building`'s place and break counters (kept per seat today, not flushed) |
| places | a once-a-second check against the markers (`BASE`, `DIAMOND`, `EMERALD`) and each map's middle |

## Decided (2026-10-19)

1. **Chests:** net per visit, as above.
2. **Mid:** a middle is the land around generators close to the center: usually the emeralds, two on most
   Triples and Quads maps, four in a ring on most Solo and Doubles maps, diamonds on Tengshe. Speedway and Screamway
   have one though their emeralds read as a line; Acropolis has none (its emeralds stretch 100 blocks along a line).
   Each map's middle is a circle drawn by hand in the Bed Wars Zones page (an oval only where a circle can't fit),
   covering the middle's own land: not bridges or walkways, and not a generator's separate island. A map without
   one keeps no `time-at-mid`.
3. **Diamond and emerald reach:** a radius around each generator: 8 blocks by default, set per map on the same page.
   Emeralds get their own `time-at-emeralds` beside `time-at-diamonds`.
4. **Public:** every stat shows in the public API.

## Built

- **2026-10-19:** the sheet (`games.stats.Tally`), `match_stats` and `match_pairs` (V6), and the career totals in
  one write per player. Counted so far: results, streaks, time played, kills and deaths by cause, finals, assists,
  beds broken and lost. V6 renamed the old `bedwars:career-*` rows to these names (`career-beds` is `beds-broken`)
  and seeded `bedwars:games` from wins and losses; streaks start from zero.
- **Resources and purchases** (`games.stats.Haul`, `Shop.buy`): every row of the resources table, and the four
  `-bought` counts.
- **Combat, items and building** (`games.stats.Feats`, `Building.Breaks`): every row of those three tables. Damage
  between two players is also kept per game as a pair, attacker against victim.
- **Places** (`bedwars.Places`, a once-a-second sampler on the sheet): every row of the places table. Each arena
  carries its middle (`bedwars:mid`) and generator reach (`bedwars:reach`), stamped from the Zones page's export
  by `gradlew :server:bedwarsZones -Pzones=<zones.json>`; a map not marked done there has neither yet, so it keeps
  no `time-at-mid` and the default 8-block reach. A `bedwarsMaps` rebuild drops them: run `bedwarsZones` after it.
- **The menu's Bed Wars card** reads the new numbers (results and ratios, finals, assists, beds, resources
  collected, damage, blocks, purchases); `/stats` in chat keeps its seven.
