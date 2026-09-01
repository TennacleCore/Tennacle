# Rulesets: modular mechanics + rules per match

Status: step 1 + the first fold are BUILT (constants; `fn:` values still reserved). Polyp has
`Builder.mutate`, generated `KNOBS` tables on every `@GenerateBuilder` config, and `PathEdits`
(members: projectiles/consumables/damage typed, attack/explosion/tnt/death/knockback flat,
fx by named handler).
Tennacle has `Ruleset` (paths + rules + fold), and bridge's three looks are rulesets - the
`arrowsVanishOver(look)` conditional is gone. Open questions below stand.

## The problem

One bridge map must play as a minemen game, a hypixel game, a scrims game, or a custom mix ("scrims,
but bow crits on"). Capture work made the constraint concrete: hypixel runs bow crits OFF in bridge and
ON in bedwars - so no setting can hang off the platform, and `arrowsVanishOver(look)` is the wrong shape.

Two failed extremes frame the design:
- **Hand-declare a key per mechanic** - horrendous. Polyp has ~340 config knobs today and grows; a
  parallel catalog would lag it forever and cap what users can touch.
- **Pass raw configs around** - the `fromBase` wipe trap (self-bow KB loss, gapple double-apply, arrow
  entry wipe were all this one bug), and nothing is serializable or discoverable.

## Three tiers of configurability

**Tier 0 - code: a profile transform.** `UnaryOperator<MechanicsProfile.Builder>`. Unlimited - lambdas,
custom behaviors, new damage types. This is what presets (mmc18, hypixel) already are. Not serializable,
never will be; it is the escape hatch and the ceiling.

**Tier 1 - mechanics paths: the DERIVED surface.** The `@GenerateBuilder` processor already enumerates
every `FieldValue` knob of every config class (it generates the builders from exactly that list). Emit
one more artifact - a knob table per config (name, value type, getter, builder setter) - and every
constant-valued mechanics knob becomes addressable with zero per-knob declarations:

```
projectiles/minecraft:arrow/critDamage = false
projectiles/minecraft:arrow/critParticles = true
consumables/minecraft:golden_apple/eatTicks = 32
damage/melee/critMultiplier = 1.5
hunger/naturalRegen = false
```

Segments: profile member -> (optional type entry, for containers with typeConfigs) -> field. Leaf codecs
come from the field's type (Boolean/Integer/Double/String/enums first; complex-typed fields such as
KnockbackConfig or Set<Key> stay Tier 0 until given codecs). Setting a path = `mutate()` + the generated
setter, so it EDITS the inherited config - a path write cannot wipe preset tuning by construction.
**Dynamic values.** A path value is not limited to constants. The grammar reserves a second form -
a reference to a REGISTERED function:

```
damage/melee/critMultiplier = 1.5                      # constant
damage/melee/critMultiplier = fn:scale-when(airborne, 2.0)   # registered FieldFn + args
```

A `FieldFn` is registered in code once (id, applicable context, arg codecs, the lambda) and the fold
BINDS the reference into a real `FieldValue.of(fn)` at launch - so there is no interpreter on the hot
path; per-hit resolution runs the same compiled lambda a preset would. Conditions draw on the existing
fact vocabulary (`CombatFacts` etc.), which is enumerable, so a UI can offer them. This is the same
pattern tier 2 already uses for rules ("values grow richer instead of becoming lambdas" -
`games:friendly-fire = off|melee|arrows|all` selects a registered HitPolicy).

What stays impossible is only user-AUTHORED lambdas as data - arbitrary code cannot be serialized
(and should not be executed if it could). Anything beyond the registered vocabulary is tier 0.
Step 1 implements constants only, but the value grammar carries the `fn:` form from day one so
dynamic values are an addition, not a redesign.

The whole tree is enumerable from the generated tables, so "what mechanics can I change" is a browse,
not a documentation page - and it never drifts from the code because it IS the code.

**Tier 2 - rule settings: StateKeys, declared by the feature that consumes them.** `cages:seconds` is
declared by the Cages module - NOT `bridge:*`; bridge is merely one installer, and any caged mode gets
the setting by installing Cages. `score:target` is declared by the scoring seam, so bridge (first to 5),
sumo (bo3), and pearl fight all expose it. `games:friendly-fire` / `games:bow-boost` belong to the
team-combat rules module. A mode supports a rule setting iff the declaring module is installed -
availability is derived from `GameType.modules(...)`, no hand-maintained list, nothing to drift.

## Ruleset

A Ruleset is a named value map over tiers 1+2, plus an optional tier-0 transform for the long tail:

```java
Ruleset SCRIMS = Ruleset.named("scrims")
        .path("projectiles/minecraft:arrow/critDamage", false)
        .path("projectiles/minecraft:arrow/critParticles", true)   // trail, no bonus (captured)
        .rule(Cages.SECONDS, 5)
        .look("scrims");                                           // default presentation, overridable

Ruleset custom = SCRIMS.with("projectiles/minecraft:arrow/critDamage", true);  // "scrims + bow crits"
```

Launch folds `GameType defaults <- ruleset <- per-match overrides` (last writer wins per key) into
exactly two outputs: a MechanicsProfile on the game's shard, and seeded StateKeys in GameState. Modules
keep reading StateKeys as they do today; nothing else ever sees the ruleset.

Because tiers 1+2 are plain `string -> value` maps they persist to the vault, ride replays, diff in a
UI, and round-trip through commands. A ruleset carrying a tier-0 transform is code-defined (a shipped
preset); user-SAVED rulesets are tiers 1+2 only - that is the natural power boundary, not an arbitrary one.

**Aliases** (optional sugar, added on demand): `bow-crits` -> the arrow critDamage path. A tiny curated
table for GUIs and chat commands; capability never depends on it.

## Look is a separate axis

Presentation (boards, banners, countdown voice, sounds) stays `Duels.byLook`. A ruleset names a default
look; an override can swap it - "scrims rules with hypixel visuals" stays expressible, and mechanics can
never ride a presentation switch again.

## Where the code lives

| layer | owns |
|-------|------|
| polyp | `Builder.mutate(key, edit)`; processor emits knob tables; a `PathEdit` applier (parse, validate, type-check, apply). No game concepts. |
| grotto | `Ruleset` (map + fold), rule-setting declaration on `GameModule`, availability check at launch. Game-agnostic. |
| tennacle | named rulesets per mode, the alias table, command/GUI surface. |

## What it buys

- `/duel bridge scrims`, a host GUI, and a saved custom setup are one code path.
- Every mechanics knob polyp ever grows is configurable the day it lands - no catalog to update.
- "What can this mode change?" = generated mechanics tree + union of installed modules' rule settings.
- Unknown path / unsupported rule / wrong type = launch-time error with a message, not a silent no-op.

## Step 1 (built)

Polyp foundation, independently useful regardless of the rest:
1. `MechanicsProfile.Builder.mutate(MechanicsKey<C>, UnaryOperator<C>)` - retires the fromBase wipe class.
2. `ConfigBuilderProcessor` additionally emits per-config knob tables; a `PathEdit` resolves and applies
   `member[/type]/field = value` through `mutate()` + generated setters.

Open (deliberately undecided): what a saved custom setup is and where it lives; per-match override
surface; alias curation; whether rule settings also get path-style addressing for uniformity; the
initial `FieldFn` vocabulary (which combinators and facts to register first).

Resolved by review:
- **Overlays cannot unset**, so presence-encoded choices ("flatDamage set means flat damage") were
  one-way from data. Fixed at the source rather than worked around: the discriminator is now one enum
  knob, `explosion/damageModel = curve|flat` (default CURVE, `flatDamage` demoted to just FLAT's
  amount), matching the `ArmorConfig.Formula` idiom. Hypixel's preset says `.damageModel(FLAT)`
  explicitly, and SkyWars' vanilla-curve TNT is one path write. **Rule for new configs: never let a
  field's presence pick a mode - name the mode.**
- **FX handlers are lambdas**, so they get names instead of paths-into-fields: `FxHandlers.register(key,
  name, handler)` and `fx/<key> = <name>` (e.g. `fx/polyp:pearl_teleport = game-wide`, `none` works for
  every key). That makes the entire `Hypixel.bedwars()` delta expressible as one line of data.
  Separately, `FxEvent` was always cancellable AND swappable, so a game-scoped listener can suppress or
  replace any effect - the registry is simply the better seam for unconditional per-world feel.

## Measured values the bridge rulesets must reproduce (captured 2026-08-31)

| knob | hypixel | scrims | minemen |
|------|---------|--------|---------|
| arrow critDamage | off | off | on (vanilla roll) |
| arrow critParticles | off | on | follow the draw |
| cage seconds | 3 | 5 | 3 |
| melee crit | x1.5 | x1.5 | x1.5 |
| armor | flat 1.8 linear | flat 1.8 linear | flat 1.8 linear |
| sword block | (d+1)/2 | (d+1)/2 | (d+1)/2 |

Bow base is vanilla `ceil(speed x 2)` everywhere; hypixel BEDWARS runs critDamage ON - settings are
per (mode, ruleset), never per platform.
