package tennacle.games.boxing;

import tennacle.Mechanics;
import tennacle.Msg;
import tennacle.games.Duels;
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
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.Countdown;
import io.github.term4.grotto.modules.Freeze;
import io.github.term4.grotto.modules.Kits;
import io.github.term4.grotto.modules.LastStanding;
import io.github.term4.grotto.modules.Phases;
import io.github.term4.grotto.modules.Score;
import io.github.term4.grotto.modules.Stats;
import io.github.term4.grotto.modules.TimeLimit;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.SlotLayout;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.grotto.vault.VaultKey;
import io.github.term4.polyp.MechanicsProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.item.component.EnchantmentList;
import net.minestom.server.item.enchant.Enchantment;
import net.minestom.server.potion.PotionEffect;
import net.minestom.server.utils.Unit;

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

    // match truth, at the seat - replayable, branch-safe
    public static final StateKey<Integer> HITS = StateKey.of("boxing:hits", Codec.INT, 0, true);
    public static final StateKey<Integer> SPRINT_HITS = StateKey.of("boxing:sprint-hits", Codec.INT, 0, true);
    public static final StateKey<Integer> COMBO = StateKey.of("boxing:combo", Codec.INT, 0, true);
    public static final StateKey<Integer> BEST_COMBO = StateKey.of("boxing:best-combo", Codec.INT, 0, true);

    /** No hunger drain and only swings hurt: what boxing IS, as paths over whatever preset the server runs. */
    static final Ruleset RULES = Ruleset.named("boxing")
            .path("hunger/exhaustionScale", 0)
            .path("damage/enabledTypes", "only(minecraft:player_attack)");

    // career truth, in the vault
    public static final VaultKey<Integer> CAREER_HITS = VaultKey.of("boxing:career-hits", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_SPRINT = VaultKey.of("boxing:career-sprint-hits", Codec.INT, 0);
    public static final VaultKey<Integer> CAREER_COMBO = VaultKey.of("boxing:career-best-combo", Codec.INT, 0);

    // deals nothing (amount is zeroed) - it's the 1.8 block-hit sprint-reset tool
    private static final ItemStack SWORD = ItemStack.of(Material.DIAMOND_SWORD)
            .with(DataComponents.CUSTOM_NAME, Component.text("Boxing Sword", NamedTextColor.YELLOW, TextDecoration.BOLD)
                    .decoration(TextDecoration.ITALIC, false))
            .with(DataComponents.ENCHANTMENTS, new EnchantmentList(Map.of(
                    Enchantment.SHARPNESS, 10, Enchantment.UNBREAKING, 10)))
            .with(DataComponents.UNBREAKABLE, Unit.INSTANCE);
    // Speed II rides the kit: a player reads it as part of the gloves
    private static final Kit KIT = Kit.of("boxing").item(0, SWORD).effect(PotionEffect.SPEED, 1);

    private Boxing() {}

    public static void install() {
        HotArena arena = HotArena.of("boxing");
        GameType type = GameType.builder("boxing")
                .slots(SlotLayout.groups(Markers.SPAWN, 1))
                .team("red", NamedTextColor.RED)
                .team("blue", NamedTextColor.BLUE)
                .expect(Markers.SPAWN, 2)
                .body(Body.fresh().gamemode(GameMode.ADVENTURE))
                .rule(Score.TARGET, 10)
                .rule(TimeLimit.LIMIT, 15L * 60 * 20)
                .modules(() -> Phases.of(Match.OPENING, Match.LIVE, Match.OVER),
                        () -> Countdown.of(Match.COUNT_FROM).grace(Match.START_GRACE_TICKS).voice(Presentation::voice),
                        () -> Freeze.during(Match.OPENING).after(Match.OVER),
                        () -> LastStanding.decides().requireAllSeatedAt(Match.LIVE),
                        () -> Score.seats(HITS).target(Score.TARGET),
                        () -> TimeLimit.of().from(Match.LIVE).onExpire(TimeLimit.lowestSeatScoreLoses(HITS)),
                        () -> Kits.of(KIT), Rules::new, Presentation::new,
                        () -> Matches.byLook(Boards::hypixel, Boards::minemen, Boards::scrims),
                        () -> Matches.byLook(Ends::hypixel, Ends::results, Ends::scrims),
                        () -> Stats.of()
                                .counter(HITS, CAREER_HITS)
                                .counter(SPRINT_HITS, CAREER_SPRINT)
                                .best(BEST_COMBO, CAREER_COMBO))
                .build();
        RULES.check(Mechanics.polyp.profiles().global());
        Duels.mode(new Mode("boxing", type, arena, MechanicsProfile::builder, RULES, Map.of(), "minemen",
                // no boxing capture - sumo's single-bold-line pattern
                List.of(Component.text("First player to land 10 hits wins!", NamedTextColor.YELLOW, TextDecoration.BOLD)),
                true, null));
        registerStats();
    }

    static int hits(Game game, Player player) {
        return player == null ? 0 : seatStat(game, player, HITS);
    }

    static int seatStat(Game game, Player player, StateKey<Integer> key) {
        Slot seat = game.roster().slotOf(player);
        return seat == null ? 0 : game.state().get(Scope.slot(seat.index()), key);
    }

    static String wtap(int hits, int sprint) {
        return hits > 0 ? Math.round(sprint * 100.0 / hits) + "%" : "N/A";
    }

    /** {@code /stats [player]}: career boxing numbers off the vault - w-tap % derives at display. */
    private static void registerStats() {
        Command stats = new Command("stats");
        var name = ArgumentType.Word("player");
        stats.setDefaultExecutor((sender, ctx) -> {
            if (sender instanceof Player p) show(p, p.getUsername(), p.getUuid());
        });
        stats.addSyntax((sender, ctx) -> {
            Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(ctx.get(name));
            if (target == null) {
                Msg.err(sender, "stats", "no player named ", Msg.v(ctx.get(name)));
                return;
            }
            show(sender, target.getUsername(), target.getUuid());
        }, name);
        MinecraftServer.getCommandManager().register(stats);
    }

    private static void show(Object to, String username, UUID uuid) {
        var vault = Matches.grotto().vault();
        vault.get(uuid, CAREER_HITS).thenAccept(hits ->
                vault.get(uuid, CAREER_SPRINT).thenAccept(sprint ->
                        vault.get(uuid, CAREER_COMBO).thenAccept(combo -> {
                            String wtap = hits > 0 ? Math.round(sprint * 100.0 / hits) + "%" : "-";
                            Msg.tag((net.minestom.server.command.CommandSender) to, "stats",
                                    Msg.v(username), "  hits ", Msg.v(hits),
                                    "  w-tap ", Msg.v(wtap), "  best combo ", Msg.v(combo));
                        })));
    }
}
