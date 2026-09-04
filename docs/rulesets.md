# Rulesets: modular mechanics + rules per match

Status: BUILT. Polyp has generated `KNOBS` tables on every `@GenerateBuilder` config and `PathEdits`
over all 23 profile members (typed families, flat configs, scalars, fx by named handler), with values
that are constants or registered `FieldFns` (`only(...)`, `flat(3.5)`, `heal-apple`). Tennacle has
`Ruleset` (scoped paths + scoped rules, the fold, `with`/`without`/`keep`/`over`, install-time `check`),
and bridge's three looks are rulesets over one base. The tier-2 rule keys are still the mode's own
(`bridge:target`, `bridge:limit-ticks`); the module-declared shape below is the intent, not the code -
`lifecycle.md` section 8 is the agreed design for it (declared by the consumer, enumerable per mode,
addressable by id).

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

**Tier 2 - rule settings: StateKeys, declared by the feature that consumes them.** `cage:seconds` is
declared by the Cages module - NOT `bridge:*`; bridge is merely one installer, and any caged mode gets
the setting by installing Cages. `score:target` is declared by the scoring seam, so bridge (first to 5),
sumo (bo3), and pearl fight all expose it. `friendly-fire` is the `FriendlyFire` module's; a bow boost is
the mechanics path `projectiles/minecraft:arrow/selfHit`. A mode supports a rule setting iff the declaring module is installed -
availability is derived from `GameType.modules(...)`, no hand-maintained list, nothing to drift.

## Ruleset

A Ruleset is a named value map over tiers 1+2, plus an optional tier-0 transform for the long tail:

```java
Ruleset SCRIMS = Ruleset.named("scrims")
        .path("projectiles/minecraft:arrow/critDamage", false)
        .path("projectiles/minecraft:arrow/critParticles", true)   // trail, no bonus (captured)
        .over(BASE);                                               // the mode's base underneath

Ruleset custom = SCRIMS.with("projectiles/minecraft:arrow/critDamage", true);  // "scrims + bow crits"
Ruleset handicap = custom.path(Scope.team("red"), "projectiles/minecraft:arrow/critDamage", false)
        .rule(Scope.team("red"), Score.TARGET, 3);                // one team's knob, one team's rule
```

Launch folds the ruleset (`apply(builder, global, game)`: game-wide entries first, then the targeted
ones as per-subject values inside the same profile) into exactly two outputs: a MechanicsProfile on the
game's shard, and seeded StateKeys in GameState. Modules keep reading StateKeys as they do today;
nothing else ever sees the ruleset. A scope the roster lacks fails the launch.

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
| grotto | `Scope` (game / team / slot) and `Rules.resolve`; no ruleset knowledge. Game-agnostic. |
| tennacle | `Ruleset` (paths + rules, scoped, the fold, boot validation), named rulesets per mode, command/GUI surface. |

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

Resolved by review - both by the same move, and it is the general rule:

**A choice between behaviours is a behaviour-typed field, and its VALUES are named factories.** Not an
enum discriminator with a parallel parameter field, and not a menu of preset instances - both cap what a
server can express to whatever someone predeclared.

- `ExplosionConfig.damageModel` is a `DamageModel` (the `BlockingBehavior`/`ConsumableBehavior` idiom):
  `DamageModel.CURVE` or `DamageModel.flat(2.0)`, one field holding the choice AND its parameter, open to
  a server's own lambda. The `flatDamage` field and its presence-magic are gone.
- Fx goes further, because "who hears it" is a second axis and pairing it with each effect would be a grid:
  `Recipients` and `FxEffect` are registered separately and composed - `to(at-listener(watchers), sound(...))`. A bare
  effect keeps the default (shard) audience. **When a vocabulary looks like the product of two lists, split
  the axes.**
- And the audience side is an ALGEBRA, not a list of scopes: primitives (`members`, `watchers`, `instance`,
  `viewers`, `source`) closed under `both` / `except` / `only` / `tree` / `within` /
  `protocol-below` / `at-listener`. "On the instance but not this shard" is `except(instance, watchers)`.
  **When you catch yourself naming the next case, name the operator instead.**
- Names must not overpromise. `everywhere` and `shard` were deleted: `everywhere` sounded server-wide but
  meant "members, full volume" (and wrongly excluded spectators), and `shard` was just `watchers` under a
  scope-sounding name. BedWars' pearl is `at-listener(watchers)`, which says exactly what it does. Only
  `predicted` keeps a name, and its doc gives the composition it equals.
- Every factory registers a SIGNATURE and a one-line doc, so `FieldFns.vocabulary(type)` prints the whole
  surface (`within(blocks, audience) - the audience, limited to that radius`) and errors name what exists.
  A vocabulary nobody can enumerate is not usable by anyone but its author.
- **Data SELECTS; it never AUTHORS.** Two shapes, and picking the wrong one is what produced every "premade
  options" smell in this design:
  - a genuine axis with few orthogonal primitives -> an ALGEBRA (fx `audience x effect`; `KeySet`
    `only(...)`/`except(...)` for which damage types are live).
  - open-ended game logic -> a REGISTRY. The behaviour is a lambda in code, where it is already unlimited,
    and `FieldFns.register` NAMES it so a rule can point at it:
    `consumables/minecraft:golden_apple/behavior = heal-apple`. No effect/condition vocabulary, because
    every primitive in such a vocabulary is itself a premade option - the exact thing being avoided.
  Bridge's whole mechanical identity is now seven paths, one of which names a behaviour.
- The world RELATION that `tree` walks lives on `MechanicsWorld` (`parent`/`children`/`family`) in the
  shared polyp-world module - not in Archipelago. Shard's existing accessors already satisfy it, so the
  tree audience works with zero Archipelago-side fx code, which is the point of the shared seam.
- `FieldFns` gives behaviour types a data grammar of named FACTORIES WITH ARGUMENTS -
  `explosion/damageModel = flat(3.5)`, `fx/polyp:pearl_teleport = to(at-listener(watchers),
  sound(entity.player.teleport, player, 1, 1))` - nested calls included. Registering a factory extends the vocabulary for that type everywhere; arguments cover
  every parameterisation, so nothing has to be enumerated in advance. Parsing happens once at fold time -
  what lands in the config is the built lambda, identical to what a preset would write.
- This is the `fn:` layer from the design, arriving where it was actually needed. The whole
  `Hypixel.bedwars()` delta is now one line of data.

Separately: `FxEvent` was always cancellable AND swappable, so a game-scoped listener can suppress or
replace any effect - the registry is simply the better seam for unconditional per-world feel.

## Measured values the bridge rulesets must reproduce (captured 2026-08-31)

| knob | hypixel | scrims | minemen |
|------|---------|--------|---------|
| arrow critDamage | off | off | on (vanilla roll) |
| arrow critParticles | off | on | follow the draw |
| cage seconds (Cages, by look) | 3 | 5 | 3 |
| melee crit | x1.5 | x1.5 | x1.5 |
| armor | flat 1.8 linear | flat 1.8 linear | flat 1.8 linear |
| sword block | (d+1)/2 | (d+1)/2 | (d+1)/2 |

Bow base is vanilla `ceil(speed x 2)` everywhere; hypixel BEDWARS runs critDamage ON - settings are
per (mode, ruleset), never per platform.

## Scoped entries: per-player variation is a value, not a scope

A game owns ONE world profile, folded once at launch and gone with the shard. A ruleset entry may carry a grotto
`Scope`, and the two halves treat it the same way:

- `path(Scope.team("red"), "projectiles/minecraft:arrow/critDamage", true)` - the knob becomes
  `FieldValue.targeted(who, value, inherited)`: red shooters get `true`, everyone else gets whatever the knob
  held after the game-wide entries folded. Nothing is written per player, so there is nothing to order, clear
  or leak, and a substitution or team switch is read live off the roster at the next resolve.
- `rule(Scope.team("red"), Respawns.RESPAWN_TICKS, 60L)` - set at that scope, where `rules().resolve(player, key)`
  already walks `slot -> team -> game`.

The subject a targeted knob tests is the entity that system resolves its profile against (`Subjects`): damage,
knockback and death are about the VICTIM; attack and projectiles about the doer. "Red takes half damage" and
"red hits harder" are therefore different members, not different scopes. Every member carries a subject - hunger, vri, item damage and player platform config too - so any
knob can be targeted.

Removing a scope (`without(Scope.team("red"))`, or `keep(s -> s instanceof Scope.Game)` for game-wide
only) is a re-fold over the base profile with the trimmed ruleset. Never edit a folded profile: a scoped knob
WRAPS the game-wide value, so the only correct way to un-wrap is to fold again without it.

Why this and not a per-player profile stack: the effective profile is a derivation of (world, game, team,
powerups), and materialising a derivation as pushed state is a cache to invalidate - every lifecycle event was
a place to get it wrong. `FieldValue` was already polyp's per-context mechanism; targeting reuses it.
Player scope stays as the plain single-tag override for dev and admin use.

