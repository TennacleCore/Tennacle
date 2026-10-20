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
| `<res>-from-players` | picked up after another player dropped it |
| `<res>-stolen` | handed over on a kill: the victim's resources, given to the killer |
| `<res>-from-chests` | taken out of a chest, net of what they put back during the same visit |
| `<res>-lost-to-players` | handed to their killer on a death |
| `<res>-lost-to-void` | carried into the void on a death with no killer, or thrown off the map |
| `<res>-spent` | paid at the shops |

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
| `items-bought`, `permanent-items-bought`, `upgrades-bought`, `traps-bought` | at the shops |
| `golden-apples-eaten`, `potions-drunk` (plus `<potion>-potions-drunk`: speed, jump, invisibility) | |
| `pearls-thrown`, `fireballs-thrown`, `tnt-placed` | |
| `silverfish-spawned`, `golems-spawned` | bedbugs and dream defenders |
| `bridge-eggs-thrown`, `popup-towers-placed`, `sponges-placed` | |

### Building
| Stat | Means |
|---|---|
| `blocks-placed`, `blocks-broken` | blocks players placed, and broke. Map blocks can't be broken, so every break is a player's block |
| `bed-defense-broken` | broken blocks that touched the bed of a team still in the game: digging into a bed's defense |

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
