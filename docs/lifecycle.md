# Game lifecycle: the layer between Grotto's Game and a mode

Status: PROPOSAL. Nothing here is built. Companion to `rulesets.md` (what a match plays) - this is
what a match IS from request to lobby.

## Where the seams are today

Grotto already owns the skeleton: `Grotto.launch` allocates the world tree, ledger, roster and
modules; `Game.join/leave/depart/disconnected` seat and unseat; `decide(outcome, linger)` then `end`
tear down through one exit policy; `GameModule` hears join, leave, reclaim-expired, decided, end.
Every mode-agnostic thing ABOVE that skeleton lives in Tennacle, hand-rolled per duel:

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

- `join` (fresh or reclaim): snapshot inventory once per player (never overwritten by a reclaim),
  clear it, apply gamemode, reset vitals, THEN dispatch `onJoin` (so `Kits` lands on an empty body).
- `leave/depart/disconnect`: modules' `onLeave` first (Speed removal, arrows), then restore.
- `end`: modules' `onEnd`, then restore for every exit before the app's exit policy. The exit policy
  keeps only what is the app's: instance, spawn, lobby boards.
- `Respawns` calls `game.body().fresh(player)` on every respawn, so a re-kit is just `Kits.apply`.

`Duels.stashKit/restoreKit/resetVitals` and the per-kind resets go.

### 2. Phases with entry ticks

`Phases` stays a module (the schedule is the type's) and gains what the duels needed by hand:

```java
Phases.of(new Phase(Match.OPENING, 0), new Phase(Match.LIVE, 0), new Phase(Match.OVER, 0))
Phases.enteredAt(game, Match.LIVE)   // STARTED_AT
Phases.enteredAt(game, Match.OVER)   // DONE_AT
Phases.is(game, Match.LIVE)          // the one check every rule makes
```

Phase names are data; Tennacle's `Match` holds the three constants every duel mode shares. `decide`
enters the last phase itself, so `OVER` and "decided" are one moment.

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
- After every `out`: teams with a standing seat; one left -> `decide(winner(team))`; none ->
  `decide(draw)`. A 2v2 loser's teammate is never crowned because the derivation is by team, not
  by "first other member".
- `requireAllSeatedAt(LIVE)`: replaces `abortIfAlone` - `Outcome.aborted()`, not a draw.

Sumo's ring-out, bridge's nothing (bridge respawns), boxing's nothing: modes call `out` only for
real eliminations.

### 4. Score and TimeLimit

```java
Score.teams(Bridge.GOALS).target(Bridge.TARGET)      // watches team-scope writes; first to target wins
Score.seats(Boxing.HITS).target(Boxing.TARGET)        // per-seat target, resolved at the scoring seat
TimeLimit.of(Sumo.LIMIT).from(Match.LIVE).onExpire(TimeLimit.draw())
TimeLimit.of(Boxing.LIMIT).from(Match.LIVE).onExpire(TimeLimit.lowestSeatScoreLoses(Boxing.HITS))
TimeLimit.of(Bridge.LIMIT_TICKS).from(Match.LIVE).onExpire(TimeLimit.highestTeamScoreWins(Bridge.GOALS))
```

`Score` rides `rules().watch` (the ledger write spine), so a scoreboard and the decider see the same
write. `TimeLimit.left(game)` replaces `Duels.timeLeft`; the clock anchors on the phase entry tick,
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

`Results` snapshots at `onDecided`, not `onEnd`: the state is final at the decision, and the body
restore at end can no longer race it. The "declare this module first" rule disappears.

## Tennacle adoption

### Mode, Look, Matches, Duels

```java
public record Mode(String name, GameType type, Supplier<Arena> arena,
                   Function<String, Ruleset> rules,      // look -> the folded ruleset (bridge/Rulesets.forLook)
                   String defaultLook, List<Component> intro) {}

Matches.start(Mode mode, @Nullable String look, List<Player> seating)
```

`Matches.start` = the one launch: configure stamps the look, folds `mode.rules(look)` into the world
profile and seeds it, then seats every player (the Body policy does the rest). `Duels` keeps the
`/duel` request/accept flow and calls it with two players; a party or a queue calls it with more.
The three `onLaunch` lambdas go.

`Look` stays the presentation preset (theme, count voice, death line, verdict, sounds, `ghostLoser`,
intro) plus the per-look module picks (`Choice.of(byLook)` for Boards and Ends). One
`Presentation` module per game subscribes to `LastStanding.onOut` (death line, status-3 pose, ghost
after 20 ticks) and `onDecided` (verdict titles, win/lose cues). `Duels.settle` splits along that
line: deciding is Grotto's, saying it is the look's.

### What each mode keeps

- sumo `Rules`: damage to zero, swings and landed counters, `standing.out(player, ring-out by
  credit)` on leaving the ring (via `Regions`), nothing else.
- boxing `Rules`: hit counters and combos; Speed II on join belongs to a `Kits`-style effect grant
  or the Body policy (`.effect(...)`), not a rule.
- bridge `Rules`: build/break gates, quiver, goal scoring increments, bow boost and friendly-fire
  gates. Deaths, falls, credit, protection and re-kit move to `Respawns` + `KillCredit` + `Limits`.

Type compositions become declarative:

```java
GameType.builder("bridge")
        .body(Body.fresh().gamemode(SURVIVAL))
        .modules(() -> Phases.of(OPENING, LIVE, OVER),
                 Cages::new,                                   // the opener
                 () -> LastStanding.decides().requireAllSeatedAt(LIVE),
                 () -> Score.teams(GOALS).target(TARGET),
                 () -> TimeLimit.of(LIMIT_TICKS).from(LIVE).onExpire(highestTeamScoreWins(GOALS)),
                 () -> Respawns.manual().anchor(RESPAWN).protect(PROTECT).onRespawn(Kits::apply),
                 KillCredit::new, () -> Limits.watch(VOID), () -> Kits.of(KIT), Rules::new,
                 Presentation::new, byLook(Boards...), byLook(Ends...))
```

## Deleted by the adoption

`Duels.PHASE/DONE_AT/STARTED_AT`, `settle` (three overloads), `draw`, `settleOnLeave`,
`abortIfAlone`, `timeLeft`, `sinceStart`, `stashKit/restoreKit/resetVitals`, `Countdown`, `Freeze`,
`Ghosting` (Tennacle copies), `Kind.onLaunch`, bridge `killed/died/announceFall/lastHit/protectedUntil`,
sumo/boxing `timeUp`, `Abandonment` for duels (LastStanding covers it; it stays for games with no
teams). Every `"fighting".equals(...)`.

## Order of work

1. Grotto: Body policy on the type + Game hooks; `Phases.enteredAt`; `LastStanding`; `Score`;
   `TimeLimit`; `Countdown`/`Freeze`/`Voice`; `KillCredit`; `Limits`; `Respawns.anchor`; Results at
   decided. Each with a `ModulesTest` case (the harness already launches games on a flat arena).
2. Tennacle: `Mode`/`Matches`/`Presentation`; sumo first (smallest), then boxing, then bridge with
   the Respawns adoption. Delete as each mode moves. The captured behaviours (beat titles, verdicts,
   goal fireworks, cage counts) stay bit-for-bit: the looks do not change, only who calls them.
3. Later, on the same shapes: parties (N seats through `Matches.start`), a queue, `/spectate`
   (`game.spectate` exists), runtime modifiers (a ruleset re-fold onto the live world profile - the
   player-config push already follows profile changes), and a `Scope.layer` rung for rules if a mode
   ever needs per-dimension rules (mechanics already vary per layer through world profiles).

## Decisions to take before step 1

1. Phase names: `opening/live/over` (proposed) or keep `countdown/fighting/done`.
2. Reclaim expiry in a 1v1 decides the game for the remaining team (proposed) rather than waiting
   for the last player to leave.
3. Results freeze at the decision, not at teardown (proposed).
4. `Countdown` and `Freeze` live in Grotto with the look supplying the voice (proposed).
5. Boxing's Speed II: a Body policy effect or a kit grant (proposed: Body policy, it is a body
   condition of the mode, not an item).
