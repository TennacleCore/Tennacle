package tennacle.games.sumo;

import io.github.term4.grotto.modules.view.Sight;
import io.github.term4.bloom.edit.Teams;
import tennacle.games.Presets;
import tennacle.Mechanics;
import tennacle.games.Duels;
import tennacle.games.Flags;
import tennacle.games.HotArena;
import tennacle.games.Match;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Presentation;
import tennacle.games.Ruleset;

import io.github.term4.grotto.Body;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.combat.Freeze;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.modules.view.Nametags;
import io.github.term4.grotto.modules.space.Daylight;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.grotto.modules.combat.KillCredit;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.space.Limits;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.GameMode;

import java.util.List;
import java.util.Map;

/**
 * Sumo: Hypixel-preset knockback, zero damage (hits shove but never hurt) - first out of the ring loses.
 * The Hypixel profile rides the game's shard (world scope beats the server global).
 * Gameplay lives in {@link Rules}, boards in {@link Boards}, end screens in {@link Ends}.
 */
public final class Sumo {

    public static final StateKey<Integer> SWINGS = StateKey.of("ledger.sumo.swings", Codec.INT, 0, true);
    public static final StateKey<Integer> LANDED = StateKey.of("ledger.sumo.landed", Codec.INT, 0, true);

    /** Only swings hurt - a selection over the damage catalog, as a path. */
    static final Ruleset BASE = Ruleset.named("sumo")
            .path("damage/enabledTypes", "only(minecraft:player_attack)")
            // captured: a duel reads health off the body, neither under the name nor in tab - on every network
            .rule(HealthDisplay.SURFACES, List.of());

    /** What each platform IS, over the base: the shove is its knockback, so the preset is the mode. */
    private static final Map<String, Ruleset> VARIANTS = Map.of(
            "hypixel", Ruleset.named("hypixel").rule(Presets.KEY, "hypixel").over(BASE),
            "scrims", Ruleset.named("scrims").rule(Presets.KEY, "scrims18").over(BASE),
            "minemen", Ruleset.named("minemen").rule(Presets.KEY, "mmc18").over(BASE));

    private Sumo() {}

    private static final Teams.Defaults DEFAULTS = new Teams.Defaults("sumo",
            io.github.term4.canvas.theme.Livery.EMPTY, java.util.List.of(io.github.term4.grotto.roster.Team.DISPLAY));

    public static void install() {
        Teams.register(DEFAULTS);
        HotArena arena = HotArena.of("sumo");
        GameType type = GameType.builder("sumo")
                .slots(SlotLayout.groups(Markers.SPAWN, 1))
                .teams(a -> tennacle.games.Palette.teams(a, Markers.SPAWN, DEFAULTS))
                .expect(Markers.SPAWN, 2)
                .expect(Markers.BOUNDS, 1) // the spectate anchor's fallback
                .expect(Markers.VOID, 1)
                .body(Body.fresh().gamemode(GameMode.ADVENTURE))
                .rule(TimeLimit.LIMIT, 2L * 60 * 20) // captured: hypixel sumo counts down from 2:00
                .modules(() -> Matches.byLook(Ends::hypixel, Ends::minemen, Ends::scrims), // first: the snapshot precedes cleanup
                        () -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of().voice(Presentation::voice),
                        () -> Freeze.during(Match.OPENING).after(Match.OVER),
                        () -> LastStanding.decides().requireAllSeatedAt(Match.LIVE),
                        () -> TimeLimit.of().from(Match.OPENING).holding(Countdown.GRACE).onExpire(TimeLimit.draw()),
                        () -> Nametags.of().relative(),
                        Spectating::new, Sight::new, Presets::new,
                        Daylight::new,
                        () -> HealthDisplay.of().inTab(g -> Matches.lookOf(g).tabHealth()),
                        () -> Limits.of().below(Markers.VOID, (p, plane) -> {
                            Game game = Game.of(p);
                            Rules rules = game != null ? game.module(Rules.class) : null;
                            if (rules != null) rules.fell(p);
                        }),
                        KillCredit::new, Rules::new, Presentation::new,
                        () -> Matches.byLook(Boards::hypixel, Boards::minemen, Boards::scrims))
                .build();
        for (Ruleset ruleset : VARIANTS.values()) ruleset.check(Mechanics.polyp.profiles().global(), type);
        Mode sumo = new Mode("sumo", type, arena, MechanicsProfile::builder,
                BASE, VARIANTS, "hypixel",
                List.of(Component.text("Eliminate your opponents!", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                true, null);
        Duels.mode(sumo);
        Flags.offer(sumo, "look", "platform", "map", "time", "preset", "daylight");
    }
}
