# Rulesets: modular mechanics + rules per match

Status: proposal. Supersedes the "Gamerules - proposed shape" sketch in NOTES.md.

## The problem

One bridge map must be playable as a minemen game, a hypixel game, a scrims game, or a custom mix
("scrims, but bow crits on"). Two kinds of setting decide that, and today they live in different worlds:

- **mechanics** - polyp `MechanicsProfile` entries (bow crits, hunger, natural regen, item toss, arrow
  behaviour, gapple). Currently assembled by hand in `Bridge.install()` as `xOver(null)` helpers.
- **rules** - `StateKey`s read at runtime (`bridge:friendly-fire`, `bridge:bow-boost`, goal target,
  time limit, cage seconds).

Neither is addressable by name, so every new setting means editing `Bridge.install`, and nothing can be
composed, persisted, or shown in a UI. Capture work forced the point: hypixel runs bow crits OFF in
bridge and ON in bedwars, so mechanics cannot hang off the platform/look - which is what
`arrowsVanishOver(look)` currently does.

## Shape

**One catalog of typed knobs; layered value maps; each knob knows how to apply itself.**

```java
// declared once, in the catalog - id, type, default, and where the value lands
Knob<Boolean> BOW_CRITS = Knob.mechanics("mechanics:bow-crits", Codec.BOOLEAN, true,
        (profile, on) -> profile.mutate(MechanicsKeys.PROJECTILES, cfg -> Arrows.crits(cfg, on)));

Knob<Boolean> FRIENDLY_FIRE = Knob.rule(Bridge.FRIENDLY_FIRE);   // a StateKey, already serializable
Knob<Integer> CAGE_SECONDS  = Knob.rule(Bridge.CAGE_SECONDS);
```

A **Ruleset** is an immutable `Knob -> value` map with a name, and composes:

```java
Ruleset HYPIXEL_BRIDGE = Ruleset.named("hypixel")
        .set(BOW_CRITS, false).set(BOW_CRIT_PARTICLES, false)
        .set(CAGE_SECONDS, 3);

Ruleset SCRIMS_BRIDGE = Ruleset.named("scrims")
        .set(BOW_CRITS, false).set(BOW_CRIT_PARTICLES, true)   // trail, no bonus (captured)
        .set(CAGE_SECONDS, 5);

Ruleset custom = SCRIMS_BRIDGE.with(BOW_CRITS, true);          // the user's "scrims + bow crits"
```

At launch the stack folds in order, last writer wins per knob:

```
GameType defaults  <-  ruleset (hypixel|scrims|minemen|saved custom)  <-  per-match overrides
```

producing exactly two outputs: a `MechanicsProfile` set on the game's shard, and the seeded rule values
in `GameState`. Nothing else in the game reads the ruleset - modules keep reading StateKeys as they do now.

## Why knobs carry VALUES, not configs

Layering whole `ProjectileConfig` / `ConsumableConfig` objects is what bit us repeatedly: type entries
merge by wholesale `putAll`, so an override silently wipes the preset's tuning (the self-bow knockback
loss, the gapple double-apply, the arrow-entry wipe - all the same bug). A knob carries a value; its
applier MUTATES the inherited config. Value merges cannot wipe anything.

That needs one polyp addition:

```java
MechanicsProfile.Builder.mutate(MechanicsKey<C> key, UnaryOperator<C> edit)
```

i.e. edit what you inherit instead of replacing it. This removes the whole `fromBase` trap class.

## Look is a separate axis

Presentation (boards, banners, countdown voice, sounds) stays `Duels.byLook`. Keeping it independent is
what makes "scrims rules with hypixel visuals" expressible, and stops mechanics from being smuggled into
a presentation switch. A ruleset MAY name a default look; an override can replace it.

## Where the code lives

| layer | owns |
|-------|------|
| polyp | `mutate()` on the profile builder. No game concepts. |
| grotto | `Knob`, `Ruleset`, and the fold into (profile, state). Game-agnostic. |
| tennacle | the catalog of knobs a mode supports + the named rulesets. |

`GameType.knob(KNOB)` declares support, mirroring `.expect(marker)` - so an unknown or unsupported knob
is a launch-time error, not a silent no-op, and a UI can enumerate exactly what a mode exposes.

## What it buys

- `/duel bridge scrims`, a host GUI, and a saved custom setup are all the same code path.
- A match config is `map<key, value>`: persists to the vault, rides replays, diffs on a scoreboard.
- A new setting = one `Knob` declaration + one applier lambda. `Bridge.install` stops growing.
- Labels + validation from the declaration, so `/gamerule` and a GUI come free.

## Migration (each step ships on its own)

1. polyp: add `MechanicsProfile.Builder.mutate`.
2. grotto: `Knob` / `Ruleset` / fold; no behaviour change yet.
3. tennacle: express today's three looks as rulesets; `Bridge.install`'s profile lambda becomes a fold.
   The `arrowsVanishOver(look)` conditional dies here.
4. per-match overrides + persistence; then the GUI.

## Measured values the bridge rulesets must reproduce

| knob | hypixel | scrims | minemen |
|------|---------|--------|---------|
| bow crit damage | off | off | on (vanilla) |
| bow crit particles | off | on | follow |
| cage seconds | 3 | 5 | 3 |
| melee crit | x1.5 | x1.5 | x1.5 |
| armor | flat 1.8 linear | flat 1.8 linear | flat 1.8 linear |
| sword block | (d+1)/2 | (d+1)/2 | (d+1)/2 |

Hypixel BEDWARS runs bow crits ON - same platform, other mode - which is why these are per (mode, ruleset)
and never per platform.
