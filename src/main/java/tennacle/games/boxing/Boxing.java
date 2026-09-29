package tennacle.games.boxing;

import tennacle.games.map.HotArena;
import tennacle.games.matchmaking.Parties;
import tennacle.games.matchmaking.Duels;
import tennacle.looks.Look;
import tennacle.looks.Palette;
import tennacle.looks.Presentation;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.flow.Abandonment;
import io.github.term4.grotto.roster.Roster;
import io.github.term4.grotto.modules.view.Refusals;
import io.github.term4.grotto.modules.view.Sight;
import io.github.term4.polyp.platform.player.PlayerNames;
import io.github.term4.bloom.mapmaking.Teams;
import tennacle.games.Presets;
import tennacle.commands.Commands;
import tennacle.commands.AppCommand;
import tennacle.boot.Mechanics;
import tennacle.Msg;
import tennacle.games.Flags;
import tennacle.games.Match;
import tennacle.games.Hits;
import tennacle.games.Matches;
import tennacle.games.Mode;
import tennacle.games.Ruleset;

import io.github.term4.grotto.Body;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Markers;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.flow.Countdown;
import io.github.term4.grotto.modules.combat.Freeze;
import io.github.term4.grotto.modules.view.HealthDisplay;
import io.github.term4.grotto.modules.view.Nametags;
import io.github.term4.grotto.modules.space.Daylight;
import io.github.term4.grotto.modules.view.Spectating;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.modules.outcome.LastStanding;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.modules.outcome.Score;
import io.github.term4.grotto.modules.outcome.Stats;
import io.github.term4.grotto.modules.flow.TimeLimit;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.vault.VaultKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.PotionEffect;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Boxing: Speed II, zero damage, first to the hit target wins. The whole gamemode IS ledger state - hits,
 * sprint (w-tap) hits, and combos live at the seats, the scoreboard displays them live, and Stats flushes
 * career totals to the vault at the end. Gameplay lives in {@link Rules}, boards in {@link Boards},
 * end screens in {@link Ends}.
 */
public final class Boxing {

    // match truth, at the seat: the shared counters, under boxing's names
    public static final StateKey<Integer> HITS = Hits.LANDED;
    public static final StateKey<Integer> SPRINT_HITS = Hits.SPRINT;
    public static final StateKey<Integer> COMBO = Hits.COMBO;
    public static final StateKey<Integer> BEST_COMBO = Hits.BEST_COMBO;

    /** No hunger drain and only swings hurt: what boxing IS, over whichever preset the platform names. */
    static final Ruleset BASE = Ruleset.named("boxing")
            .rule(Roster.RECLAIM_TICKS, 0L) // leaving is the loss
            .path("hunger/exhaustionScale", 0)
            .path("damage/enabledTypes", "only(minecraft:player_attack)")
            // captured: a duel reads health off the body, neither under the name nor in tab - on every network
            .rule(HealthDisplay.SURFACES, List.of());

    /** What each platform IS, over the base: a hit's reach and reset are its preset's, so the preset is the mode. */
    private static final Map<String, Ruleset> VARIANTS = Presets.variants(BASE);

    // career truth, in the vault
    public static final VaultKey<Integer> CAREER_HITS = VaultKey.of("boxing:career-hits", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_SPRINT = VaultKey.of("boxing:career-sprint-hits", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_COMBO = VaultKey.of("boxing:career-best-combo", Codec.INT, 0);

    // deals nothing (amount is zeroed) - it's the 1.8 block-hit sprint-reset tool
    private static final ItemStack SWORD = Kit.unbreakable(ItemStack.of(Material.DIAMOND_SWORD)
            .with(DataComponents.CUSTOM_NAME, Component.text("Boxing Sword", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false))
            .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(
                    Enchantment.SHARPNESS, 10, Enchantment.UNBREAKING, 10))));
    // Speed II rides the kit: a player reads it as part of the gloves
    private static final Kit KIT = Kit.of("boxing").item(0, SWORD).effect(PotionEffect.SPEED, 1);

    private Boxing() {}

    private static final Teams.Defaults DEFAULTS = new Teams.Defaults("boxing",
            io.github.term4.canvas.theme.Livery.EMPTY, java.util.List.of(io.github.term4.grotto.roster.Team.DISPLAY));

    public static void install() {
        Teams.register(DEFAULTS);
        HotArena arena = HotArena.of("boxing");
        GameType type = GameType.builder("boxing")
                .slots(SlotLayout.groups(Markers.SPAWN, Parties.MAX)) // a party shares its marker
                .teams(a -> tennacle.looks.Palette.teams(a, Markers.SPAWN, DEFAULTS))
                .expect(Markers.SPAWN, 2)
                .body(Body.fresh().gamemode(GameMode.ADVENTURE))
                .rule(Score.TARGET, 100)
                .rule(TimeLimit.LIMIT, 15L * 60 * 20)
                .modules(() -> Matches.byLook(Ends::hypixel, Ends::results, Ends::scrims, Ends::tennacle), // first: the snapshot precedes cleanup
                        () -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of().voice(Presentation::voice),
                        () -> Freeze.during(Match.OPENING).after(Match.OVER),
                        () -> LastStanding.decides().requireAllSeatedAt(Match.LIVE),
                        () -> Score.seats(HITS).target(Score.TARGET),
                        () -> TimeLimit.of().from(Match.OPENING).holding(Countdown.GRACE).onExpire(TimeLimit.lowestSeatScoreLoses(HITS)),
                        () -> Nametags.of().relative(),
                        Spectating::new, Sight::new, Presets::new,
                        Daylight::new,
                        () -> HealthDisplay.of().inTab(g -> Matches.lookOf(g).tabHealth()),
                        () -> Kits.of(KIT), Hits::new, Rules::new, Presentation::new, () -> Refusals.of(Look::refusal),
                        Abandonment.endWhenDeserted(Outcome::aborted),
                        () -> Matches.byLook(Boards::hypixel, Boards::minemen, Boards::scrims, Boards::tennacle),
                        () -> Stats.of()
                                .counter(HITS, CAREER_HITS)
                                .counter(SPRINT_HITS, CAREER_SPRINT)
                                .best(BEST_COMBO, CAREER_COMBO))
                .build();
        for (Ruleset ruleset : VARIANTS.values()) ruleset.check(Mechanics.polyp.profiles().global(), type);
        Mode boxing = Mode.of("boxing", type, arena, MechanicsProfile::builder, BASE, VARIANTS)
                // no boxing capture - sumo's single-bold-line pattern
                .intro(Component.text("First player to land 100 hits wins!", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .ghostLoser()
                .build();
        Duels.mode(boxing);
        Flags.offer(boxing, "preset");
        registerStats();
    }

    static String wtap(int hits, int sprint) {
        return hits > 0 ? Math.round(sprint * 100.0 / hits) + "%" : "N/A";
    }

    /** {@code /stats [player]}: career boxing numbers off the vault - w-tap % derives at display. */
    private static void registerStats() {
        AppCommand stats = Commands.player("stats", "Career boxing numbers.")
                .usage("/stats [player]", "yours, or theirs")
                .featured();
        var name = ArgumentType.Word("player");
        name.setSuggestionCallback(PlayerNames.suggest());
        stats.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) show(p, p.getUsername(), p.getUuid());
        });
        stats.addSyntax((sender, ctx) -> {
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(name));
            if (target == null) {
                Msg.fail(sender, "No player named ", Msg.v(ctx.get(name)), ".");
                return;
            }
            show(sender, target.getUsername(), target.getUuid());
        }, name);
        Commands.register(stats);
    }

    private static void show(Object to, String username, UUID uuid) {
        var vault = Matches.grotto().vault();
        var sender = (net.minestom.server.command.CommandSender) to;
        vault.get(uuid, CAREER_HITS)
                .thenCompose(hits -> vault.get(uuid, CAREER_SPRINT)
                        .thenCompose(sprint -> vault.get(uuid, CAREER_COMBO)
                                .thenAccept(combo -> {
                                    String wtap = hits > 0 ? Math.round(sprint * 100.0 / hits) + "%" : "-";
                                    Msg.say(sender, Msg.v(username), ": ", Msg.v(hits), " hits, ", Msg.v(wtap),
                                            " w-tap, best combo ", Msg.v(combo), ".");
                                })))
                .exceptionally(failed -> { // an unread failure here is a command that answers nothing at all
                    Msg.fail(sender, "Could not read those stats.");
                    return null;
                });
    }
}
