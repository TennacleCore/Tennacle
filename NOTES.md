# Status

## Mapmaker (Bloom/Barnacle)
- Starters: build the bridge by hand, mark `center` + `section <color>` regions (full structure thickness),
  `/arena save templates`. Maps: `/arena new <map> <starter> [colors...]`, tag `bridge` joins the pool.
- Section verbs: add/remove/reset (pull colors from the starter), extend/set (length, away from center),
  deepen (downward - the deck never moves), mirror (rotate markers + wand-selected blocks about the center),
  shift (whole map vertically; `bridge:height`/`bridge:void` limit planes stay pinned).
- `bridge:height` ships y=99 (y=100 is the texture-pack refused row), `bridge:void` y=80 - per-map limit
  markers carried by the starter, not code fallbacks.
- Bloom.install is API-only; Tennacle opts into `registerDefaultCommands()`. Editor lifecycle is server
  policy: /l leaves the session open, 10m empty writes `.bncl.autosave` and closes.

## Bridge (Tennacle)
- Maps: `MapPool.of("bridge")` - every arena tagged `bridge` is in rotation, named freely.
- Round: Cages owns the countdown (captured voices - hypixel 3s subtitle "Cages open in Ns..." hat/harp,
  scrims 5s + chat mirror, minemen uncaptured 3s), held "scored!" title through re-cages, firework each
  held beat. 1.5s start grace (`Duels.START_GRACE_TICKS`) before every first beat, all kinds.
- Cages authored as blocks in the `cages` arena: `cage` region + `cage` pos (stand spot, facing the front)
  per variant group; `accent`/`accent.<family>` regions recolor authored RED blocks to the seat's team
  (red/blue/lime/yellow). `/cage <name>` picks, vault-backed, prefetched at login.
- Combat: melee + arrows hurt (fatal = respawn behind 2s protection, never the death screen), hunger and
  natural regen off, gapple = full heal + absorption-if-absent (consumable config, no vanilla double-apply),
  arrows despawn on landing, no item tossing, clay drops on allowed breaks only.
- Gamerules so far: `bridge:bow-boost` (default on), `bridge:friendly-fire` (default off).
- Codes: HMAC ids, board code letter-first Crockford (scrims dim = dark gray).

## Gamerules - proposed shape (not built; rework pending)
Data in rules, lambdas in modules/kinds, platform feel in profiles:
- Rules stay StateKeys (serializable: UIs, DB, replays, /gamerule). Values grow richer instead of becoming
  lambdas (`games:friendly-fire = off|melee|arrows|all`).
- Shared catalog namespace `games:*` for cross-mode rules; mode-specific stay `bridge:*`.
- GameType declares supported rules (`.rule(KEY)` beside `.expect(...)`) - discoverable, validatable.
- Custom predicates = a Kind lambda slot (HitPolicy) selected by rule value; polyp never learns teams.

## Audit pass (done)
- PlayerPrefs: the one login-prefetch pipeline (cage picks + kit layouts ride it; new per-player data
  registers a VaultKey and reads synchronously forever after).
- Deepen follows flush floors like extend follows far ends; shift moves limits (group `pinned` stays)
  and stamp payload anchors.
- MapPool forgets deleted files; drain shared via HotArena; MarkerCommands split from BloomCommands;
  Countdown extracted from Duels; Kit carries editor display stacks; stuckInBody is a projectile knob
  (bridge sets it false - no body arrows); InventorySync tracks a SET of unverified slots.
- BridgeRoundTest: full round on a synthetic map (launch, grace, count, GO, goal + re-cage, void respawn),
  self-ticking harness via game.instance().tick(monotonic).

## Open
- Stats, end-screen win/lose sounds, Canvas screens (cage/kit/map GUIs), scrims re-capture for its
  title+sound layer, minemen bridge voice is extrapolated. Gamerules generalization awaits the rework.
- Damage MEASURED on hypixel bridge 2026-08-31 (captures/2026-08-31_23-42-09_damage, both seats, 6 armor
  = leather chest+legs+boots, no helmet - same as our kit). Every raw value is exactly vanilla 1.8:
  | quantity        | raw   | note                                            |
  |-----------------|-------|-------------------------------------------------|
  | sword hit       | 7.00  | iron sword: 1.0 player base + 6.0 modifier      |
  | crit            | 10.50 | x1.5 exactly; crit/plain ratio 1.500            |
  | armor           | x0.76 | FLAT (25-6)/25 - LEGACY_LINEAR confirmed        |
  | blocked plain   | 4.00  | (7+1)/2                                         |
  | blocked crit    | 5.75  | (10.5+1)/2                                      |
  | bow, full draw  | 6.00  | ceil(3.0 speed x 2.0); one 7.00 = faster arrow  |
  So: blocking is vanilla `(d+1)/2` (the "45%" was folklore - the cut RISES with damage, 42.9% -> 45.2%),
  and armor is flat, NOT the 1.9+ curve (Ray's "higher damage reduces armour reduction" is wrong for
  hypixel; still unmeasured on scrims).
- BOW CRITS ARE DISABLED on hypixel bridge: 0/22 arrows carried the critical metadata flag, though
  vanilla flags every full draw. Wants a projectile-config switch on ArrowEntity's roll (codegen, like
  `stuckInBody`); we currently roll the vanilla +rand(0..dmg/2+1).
- MELEE TABLE IS 1.0 LOW (all weapons, every 1.8 preset). 1.8 damage = player's 1.0 base ATTACK_DAMAGE
  plus the item modifier (`ItemSword.a = 4.0 + material`), so an iron sword totals 7.0 - measured above.
  `VanillaItems.weapons()` stores the modern totals (iron 6, diamond 7) and uses them AS the total, so
  every armed hit lands 1.0 light; the fist (1.0) is right. `ItemRegistryParityTest` golden-pins the
  wrong values. Fix = +1.0 on every weapon row + repin. NOT APPLIED - shifts TTK in every preset.
