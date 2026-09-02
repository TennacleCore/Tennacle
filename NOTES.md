# Status

## Mapmaker (Bloom/Barnacle)
- Starters: build the bridge by hand, mark `center` + `section <color>` regions (full structure thickness),
  `/arena save templates`. Maps: `/arena new <map> <starter> [colors...]`, tag `bridge` joins the pool.
- Section verbs: add/remove/reset (pull colors from the starter), extend/set (length, away from center),
  deepen (downward - the deck never moves), mirror (rotate markers + wand-selected blocks about the center),
  shift (whole map vertically; limit planes in group `pinned` stay - the starter pins `bridge:height`, not `bridge:void`).
- `bridge:height` ships y=99 (y=100 is the texture-pack refused row), `bridge:void` y=80 - per-map limit
  markers carried by the starter, not code fallbacks.
- Bloom.install is API-only; Tennacle opts into `registerDefaultCommands()`. Editor lifecycle is server
  policy: /l leaves the session open, 10m empty writes `.bncl.autosave` and closes.

## Bridge (Tennacle)
- Maps: `MapPool.of("bridge")` - every arena tagged `bridge` is in rotation, named freely.
- Round: Cages owns the countdown (captured voices - hypixel 3s subtitle "Cages open in Ns..." hat/harp,
  scrims 5s + chat mirror, minemen uncaptured 3s), held "scored!" title through re-cages, firework each
  held beat. 2s start grace (`Duels.START_GRACE_TICKS`) before every first beat, all kinds.
- Cages authored as blocks in the `cages` arena: `cage` region + `cage` pos (stand spot, facing the front)
  per variant group; `accent`/`accent.<family>` regions recolor authored RED blocks to the seat's team
  (red/blue/lime/yellow). `/cage <name>` picks, vault-backed, prefetched at login.
- Combat: melee + arrows hurt (fatal = respawn behind 2s protection, never the death screen), hunger and
  natural regen off, gapple = full heal + absorption-if-absent (consumable config, no vanilla double-apply),
  arrows despawn on landing, no item tossing, clay drops on allowed breaks only.
- Gamerules so far: `bridge:bow-boost` (default on), `bridge:friendly-fire` (default off).
- Codes: HMAC ids, board code letter-first Crockford (scrims dim = dark gray).

## Rulesets (modular mechanics + rules)
See `docs/rulesets.md`. Built: every `@GenerateBuilder` knob is path-addressable through the generated KNOBS
tables and polyp `PathEdits` (all 23 profile members); tennacle `Ruleset` = scoped paths (grotto `Scope`: game,
team, seat) + scoped rule StateKeys, folded into the game's ONE world profile at launch
(`apply(b, fallback, game)`) and seeded into GameState; `with`/`without`/`keep`/`over` derive variants; every
mode `check`s its rulesets at install, so a bad path fails at boot and a scope the roster lacks fails at launch,
never at a match. Path values are constants or registered FieldFns (`only(...)`, `heal-apple`,
`to(at-listener(watchers), sound(...))`). Bridge = `bridge/Rulesets`: BASE + one look preset over it per platform.

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
- Damage MEASURED 2026-08-31 on four sessions (minemen-capture/captures/{hpxlbridge,hpxlbw,scrimsbridge,
  mmcbridge}, duo captures, `analyze_damage.py` merges both seats' clocks off their log headers):
  | server / mode      | armor | sword | crit  | ratio | bow full  | bow crits          |
  |--------------------|-------|-------|-------|-------|-----------|--------------------|
  | hypixel bridge     | 6     | 7.00  | 10.50 | 1.500 | 6 flat    | OFF                |
  | scrims bridge      | 6     | 7.02  | 10.53 | 1.500 | 6 flat    | OFF (flag on only) |
  | minemen bridge     | 6     | 7.00  | 10.50 | 1.500 | 6..9      | ON                 |
  | hypixel bow duel   | 7     | 5.00  | 7.50  | 1.500 | 7..11     | ON                 |
  All raw (pre-armor). Every bridge kit = 6 armor (leather chest+legs+boots, no helmet) + iron sword.
  - CRIT is x1.5 exactly and ARMOR IS FLAT LINEAR on all four (ratio 1.500 to 3 decimals; the 1.9+ curve
    would give ~1.6). Ray's "higher damage reduces the armour reduction" is wrong even on scrims.
  - BLOCKING = vanilla `(d+1)/2` (hypixel: 4.00 blocked from 7.00, 5.75 from 10.50). The "45%" is folklore
    - the cut RISES with damage (42.9% -> 45.2%). Already what `Vanilla18.Blocking` computes.
  - BOW CRITS off on hypixel+scrims bridge, ON for minemen bridge -> wants a per-profile projectile knob
    on ArrowEntity's roll (codegen, like `stuckInBody`), not a global change.
  - Reading captures: hypixel never sets the arrow critical metadata flag (even in bow duels where the
    roll is clearly live), and scrims sets it with no damage bonus - judge by the damage SPREAD (~4 wide
    when live), never the flag. Scrims also rounds health to integers on the wire (deltas alternate 5/6).
- MELEE TABLE FIXED (was 1.0 low in every 1.8 preset). Verified in paper 1.8: `EntityHuman.initAttributes`
  sets base ATTACK_DAMAGE 1.0, items add an operation-0 (additive) modifier - sword `4+mat`, axe `3+`,
  pickaxe `2+`, shovel `1+` - and `attack()` reads the TOTAL. So iron sword 7, wood 5, diamond 8. Both
  captures agree (bridge iron 7.00, bedwars wood 5.00). `VanillaItems.weapons()` had stored the modern
  totals; every row +1, `ItemRegistryParityTest` repinned, and 13 golden values across the attribute /
  armor / resistance tests moved with it (all re-derived by hand, not accepted blind).
- BOW CRITS: `critDamage` and `critParticles` are now separate tri-state projectile knobs (null = follow
  the arrow). Bridge sets them per look - hypixel both off, scrims trail-only (flag TRUE, roll off),
  minemen vanilla. `ArrowCritKnobsTest` pins the flag in all three directions.
