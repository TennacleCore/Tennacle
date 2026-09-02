# Game lifecycle: the layer between Grotto's Game and a mode

Status: BUILT (steps 1 and 2; step 3 is the growth list). Companion to `rulesets.md` (what a match
plays) - this is what a match IS from request to lobby. Three nouns, kept apart on purpose: a MODE is the game (bridge), a
RULESET is its gamerules (crit bows off, five-second cages), a LOOK is only how it speaks (boards,
chat, titles, cues). A PLATFORM bundles one ruleset with one look under the name a player says
(`hypixel`), and nothing but the command needs the bundle.

## Where the seams were

Grotto already owns the skeleton: `Grotto.launch` allocates the world tree, ledger, roster and
modules; `Game.join/leave/depart/disconnected` seat and unseat; `decide(outcome, linger)` then `end`
tear down through one exit policy; `GameModule` hears join, leave, reclaim-expired, decided, end.
Before the build, every mode-agnostic thing ABOVE that skeleton lived in Tennacle, hand-rolled per duel:

| concern | today | shape |
|---|---|---|
| phase | `Duels.PHASE` string + `STARTED_AT`/`DONE_AT`, checked as `"fighting".equals(...)` in every Rules, Freeze, Countdown, Cages | Grotto's `Phases` module exists and nobody uses it |
| body | `Duels.start` stashes the kit, sets adventure, resets vitals; bridge `refit`; boxing adds/removes Speed; `lobbyRoute` resets again on exit | four owners of one thing, none of them the type |
| opening | `Countdown` (grace, beats, GO) + `Freeze` (pin, swallow KB); bridge `Cages` repeats the count | one opener contract, two implementations |
| deciding | `Duels.settle` (loser -> team -> winner -> decide + death line + verdict + ghost), `draw`, `settleOnLeave`, `abortIfAlone`, three `timeUp`s, boxing's hit target, bridge's goal target | outcome derivation fused with presentation |
| forfeit | `settleOnLeave` settles when the seat is not reserved; an EXPIRED reservation decides nothing | the reclaim window has no ending |
| respawn | bridge `killed`/`died` teleport + refit + protection + kill credit by hand | Grotto `Respawns` exists, unadopted |
| boundaries | sumo ring by region contains, bridge void by a y compare per move packet | `Regions` exists; limit planes have no module |
| entry | `Duels.start` = arena supply + launch configure (look stamp, ruleset fold) + seating, once per kind | one `Matches.start` for any seating |

The rule for the split, same as rulesets: Grotto holds what every mode needs and no mode can get
wrong differently; Tennacle holds the modes, their looks and their matchmaking.

## Grotto additions

### 1. Body policy on the type, applied by the Game

Not a module - modules race each other (Results "DECLARE FIRST", Kits before or after the stash).
The type declares what a member's body is on entry and what happens to it on exit; the Game applies
it at fixed points no module can reorder.

```java
GameType.builder("bridge")
        .body(Body.fresh()                 // health, food, saturation, effects, exp, fire, absorption, velocity
                .gamemode(GameMode.SURVIVAL)
                .keepInventory(false))     // snapshot on entry, restore on exit (default)
```

The snapshot is the whole body the game may touch: inventory, gamemode, flying and allow-flying
(a ghosted loser hovers), plus the vitals list above. Restore means the snapshot, not "the lobby's
idea of fresh" - the exit policy stops knowing anything about bodies.

- `join` (fresh or reclaim): snapshot inventory once per player (never overwritten by a reclaim),
  clear it, apply gamemode, reset vitals, THEN dispatch `onJoin` (so `Kits` lands on an empty body).
- `leave/depart/disconnect`: modules' `onLeave` first (Speed removal, arrows), then restore.
- `end`: modules' `onEnd`, then restore for every exit before the app's exit policy. The exit policy
  keeps only what is the app's: instance, spawn, lobby boards.
- `Respawns` calls `game.body().fresh(player)` on every respawn, so a re-kit is just `Kits.apply`.
- Effects are kit-borne, never body policy: players read a Speed II as part of the boxing kit, so the
  kit says so (`Kit.effect(SPEED, 1)`), `Kits` applies effects with the items, `fresh` clears them
  first, and the kit editor shows them. Body policy is health, food, gamemode and the inventory
  stash only.

`Duels.stashKit/restoreKit/resetVitals` and the per-kind resets go.

### 2. Phases with entry ticks

`Phases` stays a module (the schedule is the type's) and gains what the duels needed by hand:

```java
Phases.of(new Phase(Match.OPENING, 0), new Phase(Match.LIVE, 0), new Phase(Match.OVER, 0))
Phases.enteredAt(game, Match.LIVE)   // STARTED_AT
Phases.enteredAt(game, Match.OVER)   // DONE_AT
Phases.is(game, Match.LIVE)          // the one check every rule makes
```

Phase names are data; Tennacle's `Match` holds the three constants every duel mode shares. `Phases`
enters its last phase from `onDecided`, so `OVER` and "decided" are one moment; a game that ends
without deciding enters it from `onEnd`.

### 3. LastStanding: who is out, and when that ends the game

One module derives the outcome from seats. Nothing else calls `decide` for an elimination.

```java
public final class LastStanding implements GameModule {
    public record Cause(@Nullable Player by, @Nullable String verb, Kind kind) {  // ELIMINATED, FORFEIT, NEVER_ARRIVED
    public void out(Player player, Cause cause);          // marks Scope.slot OUT, fires onOut
    public LastStanding onOut(OutListener listener);      // presentation hooks here
    public LastStanding requireAllSeatedAt(String phase); // a team with nobody at GO -> aborted
    public LastStanding linger(long ticks);
}
```

- `onLeave`: seat null -> ignore (spectators settle nothing); reserved -> wait; else `out(FORFEIT)`.
- `onReclaimExpired`: `out(FORFEIT)` - the window finally has an ending.
- After every `out`: a team STANDS while it has a present member or a live reservation. One team
  standing -> `decide(winner(team))`, unless nobody on it is present (both sides walked out with
  windows open): then wait - a return wins, its own expiry draws. None standing -> `decide(draw)`.
  A 2v2 loser's teammate is never crowned because the derivation is by team, not by "first other
  member".
- `requireAllSeatedAt(LIVE)`: replaces `abortIfAlone` - `Outcome.aborted()`, not a draw.

Sumo's ring-out, bridge's nothing (bridge respawns), boxing's nothing: modes call `out` only for
real eliminations.

### 4. Score and TimeLimit

```java
Score.teams(Bridge.GOALS).target(Score.TARGET)        // watches team-scope writes; first to target wins
Score.seats(Boxing.HITS).target(Score.TARGET)         // per-seat target, resolved at the scoring seat
TimeLimit.of().from(Match.LIVE).onExpire(TimeLimit.draw())
TimeLimit.of().from(Match.LIVE).onExpire(TimeLimit.lowestSeatScoreLoses(Boxing.HITS))
TimeLimit.of().from(Match.LIVE).onExpire(TimeLimit.highestTeamScoreWins(Bridge.GOALS))
```

`Score` rides `rules().watch`, which already reports the scope of every committed write, so a
scoreboard and the decider see the same write and the decider knows which team or seat crossed. `TimeLimit.left(game)` replaces `Duels.timeLeft`; the clock anchors on the phase entry tick,
so grace and countdowns never eat it. Ties resolve to a draw unless the decider says otherwise.

### 5. Countdown and Freeze, with a Voice seam

The opener is mode-agnostic; only its voice is a look. Both move into Grotto:

```java
Countdown.of(5).grace(40).voice(look::count)   // beat(game, n) / go(game); drives Phases.advance()
Freeze.during(Match.OPENING)                   // pin to the seat, swallow knockback
```

`Freeze` keeps the settling-hit exception (knockback lands on the tick `OVER` was entered, never
later) through `Phases.enteredAt`. Bridge's `Cages` implements the same `Opener` contract with
blocks: it advances `Phases` at zero like `Countdown` does, so every rule reads one phase.

### 6. Respawns adopted, with the three things bridge added by hand

- `anchor(MarkerKey<PosMarker> perTeam)`: respawn at `bridge:respawn` for the seat's team, the seat
  spawn otherwise.
- `KillCredit` module: last damager per victim within `CREDIT_TICKS`; `creditFor(victim)` at death
  or fall. Bridge's `lastHit` map, sumo's "knocked out of the ring by" both read it.
- `Limits` module: `watch(Bridge.VOID).below((game, player) -> ...)` for limit planes, the way
  `Regions` watches boxes. A fall is `respawns.queue(player)` plus `KillCredit`, not a move handler.

`FatalDamageEvent` cancel + queue is what `Respawns.auto()` already does; bridge's custom death
becomes `manual()` + `queue`.

### 7. Results capture at the bell

`Results` snapshots at `onDecided`, not `onEnd` (at `onEnd` only for a game that ends without
deciding): the state is final at the decision, and the body restore at end can no longer race it.
The "declare this module first" rule disappears.

### 8. Rule keys: the gamerules that are not mechanics

Arrow cooldown, points to win, cage seconds, spawn protection, whether cages happen at all, whether
players re-kit between goals: none of these is a polyp knob, and today half of them are constants
(`ARROW_TICKS = 70`, `CREDIT_TICKS = 100`, cages always, refit always) and the other half are
`StateKey`s a Java constant alone can name. The mechanism is already there - a rule is a `StateKey`
with a default on the type, seeded per scope by a ruleset, resolved seat -> team -> game by
`Rules.resolve` - so the work is the shape around it, not a new tier:

- **Declared by the consumer.** A module lists the keys it reads:
  `default Set<StateKey<?>> rules()` on `GameModule`. `Score` declares `grotto:score:target`,
  `Respawns` its two windows, `KillCredit` its credit ticks, `TimeLimit` its limit, `Cages`
  `cages:seconds` and `cages:enabled`, bridge `Rules` `bridge:arrow-ticks` and
  `bridge:refit-on-goal`, `bridge:bow-boost`, `bridge:friendly-fire`. A generic seam declares a
  generic key so every mode that installs it exposes the same setting; a mode-specific behaviour
  declares a mode-specific key. No constant that a platform or a player could want to vary stays a
  constant.
- **Enumerable per mode, derived.** `GameType.supportedRules()` = the type's own defaults plus the
  union of its modules' declarations (factories build a module to ask; constructors do no work,
  `install` does). `Choice` declares its variants up front (`Choice.of(pick, variants...)`, `byLook`
  is its one caller) so a per-look pick still enumerates. No static registry anywhere: the list is
  derived from the type, which is exactly what kept `Rulesets` out of the old boot sweep. That is the whole "what can I change in bridge" list: id, doc, default, codec -
  nothing hand-maintained, nothing to drift, the same promise the mechanics KNOBS tables keep.
- **Addressable from data.** `Ruleset.rule("bridge:arrow-ticks", "60")` next to `.rule(KEY, 60)`:
  the id resolves through the mode's supported rules, the value parses through the key's Minestom
  codec over `Transcoder.JSON` (a bare word reads as a JSON string; a list-valued key stays
  code-only, like a generic knob). `check` reports an unknown
  id, an unparsable value, or a key no installed module reads ("bridge has no rule cages:seconds
  without Cages") - the availability rule, enforced where a typo is cheapest.
- **Structural rules read at install, live rules at use.** `cages:enabled=false` makes `Cages` a
  plain opener with no re-cage between goals and lets `Respawns` respawn normally; `bridge:refit-on-goal`
  is read at each goal. Both work because the launch configure folds and seeds BEFORE modules
  install, which the fold already guarantees.
- **Per scope, as today.** `.rule(Scope.team("red"), Score.TARGET, 3)` is a handicap; a rule read
  for a player resolves through their seat and team first.

`GameType.rules()` stays the defaults; `Ruleset` stays the two halves (mechanics paths, rule keys);
a saved custom ruleset or a settings GUI writes both as data and never needs a Java constant.

## Tennacle adoption

### Mode, Ruleset, Look, Platform, Matches, Duels

```java
public record Mode(String name, GameType type, Supplier<Arena> arena,
                   Map<String, Ruleset> rulesets,        // named, each already over the mode's base
                   List<Component> intro, boolean ghostLoser) {}
public record Platform(String name, String ruleset, String look) {}   // what a player says: hypixel

Matches.start(Mode mode, Ruleset rules, Look look, List<Player> seating)
```

`Matches.start` = the one launch: configure stamps the look name, folds `rules` into the world
profile and seeds it, then seats every player (the Body policy does the rest). `Duels` keeps the
`/duel <player> <mode> [platform]` flow, resolves the platform to its ruleset and look, and calls it
with two players; a party or a queue calls it with more; a settings GUI later picks the two halves
separately. The three `onLaunch` lambdas go.

Rulesets carry every gamerule a platform differs on: the arrow crit knobs (`bridge/Rulesets`), and
now the cage count too - `.rule(Cages.SECONDS, 5)` in scrims, the key declared by `Cages` as the
original design said, no longer a look switch inside the module.

`Look` is presentation only: theme, count voice, death line, verdict, sounds, the intro block, and
the per-look module picks (`Choice.of(byLook)` for Boards and Ends). It reads game state (a team
score key, the phase) and never sets a rule. One `Presentation` module per game subscribes to
`LastStanding.onOut` (death line, status-3 pose, ghost after 20 ticks when the mode ghosts losers)
and `onDecided` (verdict titles, win/lose cues). `Duels.settle` splits along that line: deciding is
Grotto's, saying it is the look's.

### What each mode keeps

- sumo `Rules`: damage to zero, swings and landed counters, `standing.out(player, ring-out by
  credit)` on leaving the ring (via `Regions`), nothing else.
- boxing `Rules`: hit counters and combos; Speed II is on the kit (`KIT.effect(SPEED, 1)`), so it
  arrives with the gloves and leaves with them.
- bridge `Rules`: build/break gates, quiver, goal scoring increments, bow boost and friendly-fire
  gates. Deaths, falls, credit, protection and re-kit move to `Respawns` + `KillCredit` + `Limits`.

Type compositions become declarative:

```java
GameType.builder("bridge")
        .body(Body.fresh().gamemode(SURVIVAL))
        .modules(() -> Phases.of(OPENING, LIVE, OVER),
                 Cages::new,                                   // the opener; its seconds are a seeded rule
                 () -> LastStanding.decides().requireAllSeatedAt(LIVE),
                 () -> Score.teams(GOALS).target(TARGET),
                 () -> TimeLimit.of(LIMIT_TICKS).from(LIVE).onExpire(highestTeamScoreWins(GOALS)),
                 () -> Respawns.manual().anchor(RESPAWN).protect(PROTECT).onRespawn(Kits::apply),
                 KillCredit::new, () -> Limits.watch(VOID), () -> Kits.of(KIT), Rules::new,
                 Presentation::new, byLook(Boards...), byLook(Ends...))
```

## Deleted by the adoption (done)

`Duels.PHASE/DONE_AT/STARTED_AT`, `settle` (three overloads), `draw`, `settleOnLeave`,
`abortIfAlone`, `timeLeft`, `sinceStart`, `stashKit/restoreKit/resetVitals`, `Countdown`, `Freeze`,
`Ghosting` (Tennacle copies), `Kind.onLaunch`, bridge `killed/died/announceFall/lastHit/protectedUntil`,
sumo/boxing `timeUp`, `Abandonment` for duels (LastStanding covers it; it stays for games with no
teams). Every `"fighting".equals(...)`.

## Order of work

1. Grotto: Body policy on the type + Game hooks; `Phases.enteredAt`; `LastStanding`; `Score`;
   `TimeLimit`; `Countdown`/`Freeze`/`Voice`; `KillCredit`; `Limits`; `Respawns.anchor`; Results at
   decided; `GameModule.rules()` + `GameType.supportedRules()` + string parsing on the scalar
   `StateKey` codecs. Each with a `ModulesTest` case (the harness already launches games on a flat
   arena).
2. Tennacle: `Mode`/`Matches`/`Presentation`; sumo first (smallest), then boxing, then bridge with
   `TestWiring`, `BridgeRoundTest` and `DuelCommandTest` rewritten against `Matches` and `Phases`
   as each mode moves (they read `Duels.PHASE` and `onLaunch` today), and bridge takes the Respawns
   adoption. Delete as each mode moves. The captured behaviours (beat titles, verdicts,
   goal fireworks, cage counts) stay bit-for-bit: the looks do not change, only who calls them.
   Every gameplay constant becomes a declared rule key on the way (`Ruleset.rule(id, value)` gets
   its `check` against the mode's supported rules at the same time).
3. Later, on the same shapes: parties (N seats through `Matches.start`), a queue, `/spectate`
   (`game.spectate` exists), runtime modifiers (a ruleset re-fold onto the live world profile - the
   player-config push already follows profile changes), and a `Scope.layer` rung for rules if a mode
   ever needs per-dimension rules (mechanics already vary per layer through world profiles).

## Decisions taken

1. Phase names are `opening / live / over`.
2. Reclaim expiry in a 1v1 decides the game for the remaining team.
3. Results freeze at the decision, not at teardown.
4. `Countdown` and `Freeze` live in Grotto; the look supplies only the voice.
5. Potion effects are kit-borne (`Kit.effect`), never body policy.
6. A look never carries a gamerule; the platform's rules are a named ruleset, the cage seconds
   included. `Platform` is the only place a ruleset and a look are paired.
7. Gamerules that are not mechanics are rule keys declared by the module that reads them, enumerable
   per mode, addressable from data by id; no gameplay constant stays a constant.
