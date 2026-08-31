package tennacle.games;

import tennacle.Mechanics;
import tennacle.Msg;

import io.github.term4.barnacle.Arena;
import io.github.term4.canvas.theme.HypixelIntro;
import io.github.term4.canvas.theme.SoundSet;
import io.github.term4.canvas.theme.Titles;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.GameType;
import io.github.term4.grotto.Grotto;
import io.github.term4.grotto.Outcome;
import io.github.term4.grotto.modules.Results;
import io.github.term4.grotto.modules.Summary;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.roster.Team;
import io.github.term4.grotto.state.GameState;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import io.github.term4.archipelago.shard.ShardManager;
import io.github.term4.polyp.MechanicsKeys;
import io.github.term4.polyp.MechanicsProfile;
import io.github.term4.polyp.api.event.knockback.KnockbackEvent;
import io.github.term4.polyp.mechanics.damage.DamageConfig;
import io.github.term4.polyp.mechanics.damage.types.DamageTypeConfig;
import io.github.term4.polyp.mechanics.damage.types.breathing.DrowningDamage;
import io.github.term4.polyp.mechanics.damage.types.breathing.SuffocationDamage;
import io.github.term4.polyp.mechanics.damage.types.burning.BurningDamage;
import io.github.term4.polyp.mechanics.damage.types.burning.InFireDamage;
import io.github.term4.polyp.mechanics.damage.types.burning.LavaDamage;
import io.github.term4.polyp.mechanics.damage.types.cactus.CactusDamage;
import io.github.term4.polyp.mechanics.damage.types.explosion.ExplosionDamage;
import io.github.term4.polyp.mechanics.damage.types.fall.FallDamage;
import io.github.term4.polyp.mechanics.damage.types.generic.GenericDamage;
import io.github.term4.polyp.mechanics.damage.types.magic.MagicDamage;
import io.github.term4.polyp.mechanics.damage.types.projectile.ProjectileDamage;
import io.github.term4.polyp.mechanics.damage.types.starvation.StarvationDamage;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.entity.attribute.Attribute;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.EntityStatusPacket;
import net.minestom.server.sound.SoundEvent;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The duel front shared by every 1v1 kind: {@code /duel <player> [mode]} requests, dueling back accepts
 * (the REQUESTER's mode wins), kits stash for the fight and restore at the exit, and the frozen
 * countdown is one module for all kinds (the look voices it). Kinds register at boot (sumo, boxing, ...).
 */
public final class Duels {

    /** {@code "countdown" -> "fighting" -> "done"} - every duel kind's modules read this. */
    public static final StateKey<String> PHASE = StateKey.of("duel:phase", Codec.STRING, "countdown");
    // the tick the duel settled: the final hit's knockback (same cascade, same tick) must still land
    public static final StateKey<Long> DONE_AT = StateKey.of("duel:done-at", Codec.LONG, -1L);

    /** Renders a kill line; {@code winner}/{@code verb} null = a forfeit with no killer. */
    @FunctionalInterface
    public interface DeathLine {
        Component render(Game game, Player loser, @Nullable Player winner, @Nullable String verb);
    }

    /** Shows the end-of-match title to one viewer. */
    @FunctionalInterface
    public interface Verdict {
        void show(Game game, Player viewer, boolean won, Player winner);
    }

    /** The start countdown's voice: chat line, optional title per beat, and the at-zero line. */
    public record Count(@NotNull IntFunction<Component> number,
                        @Nullable IntFunction<Component> title,
                        @NotNull BiFunction<Game, Player, @Nullable Component> started) {}

    // minemen/scrims cues aren't captured yet - the era-standard pling
    private static final Sound TICK = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 1f);
    private static final Sound GO = Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.MASTER, 1f, 2f);

    /** A presentation preset - chat theme, kill line, verdicts, countdown voice, sound cues - one unit per kind. */
    public record Look(@NotNull io.github.term4.canvas.theme.Theme theme, @NotNull DeathLine death,
                       @NotNull Verdict verdict, @NotNull Count count, @NotNull SoundSet sounds) {}

    /** Yellow names, gray prose, VICTORY!/DEFEATED! - the minemen profile. */
    public static final Look MINEMEN = new Look(Msg.MINEMEN, Duels::minemenDeath, Duels::minemenVerdict,
            new Count(n -> Component.text(n + "...", NamedTextColor.YELLOW), null,
                    (g, p) -> Component.text("Match started!", NamedTextColor.GREEN)),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO));
    /** Team-colored names, gray prose, gold Victory!/red Defeat - the scrims profile. */
    public static final Look SCRIMS = new Look(Msg.SCRIMS, Duels::scrimsDeath, Duels::scrimsVerdict,
            new Count(Duels::scrimsNumber, null, (g, p) -> null),
            SoundSet.of().cue(SoundSet.COUNTDOWN, TICK).cue(SoundSet.START, GO));
    /** Captured hypixel: scoring kinds end on TEAM WINS! + the score; plain duels on VICTORY!/DEFEAT!. */
    public static final Look HYPIXEL = new Look(Msg.HYPIXEL, Duels::hypixelDeath, Duels::hypixelVerdict,
            new Count(Duels::hypixelNumber,
                    n -> Component.text(n, n >= 4 ? NamedTextColor.YELLOW : NamedTextColor.RED),
                    Duels::hypixelStarted),
            // captured: note.hat vol 20 each beat, nothing at zero, fireworks.launch vol 3 on a goal
            SoundSet.of()
                    .cue(SoundSet.COUNTDOWN,
                            Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_HAT, Sound.Source.MASTER, 20f, 1f))
                    .cue(SoundSet.SCORED,
                            Sound.sound(SoundEvent.ENTITY_FIREWORK_ROCKET_LAUNCH, Sound.Source.MASTER, 3f, 1f)));

    /**
     * {@code ghostLoser}: death animation + observer spectate; off = everyone just stays put (bridge).
     * {@code intro}: instruction lines for hypixel's game-start block; empty skips the block.
     * {@code teamScore}: per-team score key marking a SCORING kind (drives hypixel's WINS! verdict);
     * null = a plain duel.
     */
    public record Kind(@NotNull String name, @NotNull GameType type, @NotNull Supplier<Arena> arena,
                @NotNull Consumer<Game> onLaunch, @NotNull Look look, boolean ghostLoser,
                @NotNull List<Component> intro, @Nullable StateKey<Integer> teamScore) {
        public Kind(@NotNull String name, @NotNull GameType type, @NotNull Supplier<Arena> arena,
             @NotNull Consumer<Game> onLaunch) {
            this(name, type, arena, onLaunch, MINEMEN, true, List.of(), null);
        }
        public Kind(@NotNull String name, @NotNull GameType type, @NotNull Supplier<Arena> arena,
             @NotNull Consumer<Game> onLaunch, @NotNull Look look, boolean ghostLoser) {
            this(name, type, arena, onLaunch, look, ghostLoser, List.of(), null);
        }
        public Kind(@NotNull String name, @NotNull GameType type, @NotNull Supplier<Arena> arena,
             @NotNull Consumer<Game> onLaunch, @NotNull Look look, boolean ghostLoser,
             @NotNull List<Component> intro) {
            this(name, type, arena, onLaunch, look, ghostLoser, intro, null);
        }
    }

    /** A duel-time override ({@code /duel <player> <mode> <look>}); empty = the kind's default. */
    public static final StateKey<String> LOOK = StateKey.of("duel:look", Codec.STRING, "");

    /** The named presentation profiles a duel can pick at request time. */
    private static final Map<String, Look> looks = new LinkedHashMap<>(Map.of(
            "minemen", MINEMEN, "scrims", SCRIMS, "hypixel", HYPIXEL));

    /** The game's presentation preset: the duel-time override when set, else the kind's default. */
    /** The one other side in a 1v1 (ghosts included, so boards hold through the linger), or {@code null}. */
    public static @Nullable Player opponent(@NotNull Game game, @NotNull Player me) {
        return game.opponents(me).stream().findFirst().orElse(null);
    }

    /** End screens carrying the game's look - what every duel mode's {@code Ends} starts from. */
    public static Summary summary() {
        return Summary.of().theme(g -> lookOf(g).theme());
    }

    public static Results results() {
        return Results.of().theme(g -> lookOf(g).theme());
    }

    public static Look lookOf(Game game) {
        Look override = looks.get(game.state().get(Scope.GAME, LOOK));
        if (override != null) return override;
        Kind kind = kinds.get(game.type().id());
        return kind != null ? kind.look() : MINEMEN;
    }

    /** The registered kind behind a running game (by its GameType id), or null for a foreign game. */
    public static @Nullable Kind kindOf(Game game) {
        return kinds.get(game.type().id());
    }

    /** {@link #lookOf} as its registry name - what a structural variant switches on. */
    public static String lookNameOf(Game game) {
        Look look = lookOf(game);
        for (Map.Entry<String, Look> entry : looks.entrySet()) {
            if (entry.getValue() == look) return entry.getKey();
        }
        return "minemen";
    }

    private record Request(UUID target, String kind, @Nullable String look, long at) {
        boolean expired() {
            return System.currentTimeMillis() - at > 60_000;
        }
    }

    private static final Map<String, Kind> kinds = new LinkedHashMap<>();
    private static final Map<UUID, Request> requests = new ConcurrentHashMap<>();
    private static final Map<UUID, ItemStack[]> kits = new ConcurrentHashMap<>();
    private static Grotto grotto;
    private static String defaultKind;
    private static Consumer<Player> lobbyRoute = player -> {};

    private Duels() {}

    public static Grotto grotto() {
        return grotto;
    }

    /** The exit-policy teleport (gamemode + kit restore included) - for commands that send players home. */
    public static void toLobby(@NotNull Player player) {
        lobbyRoute.accept(player);
    }

    public static void kind(@NotNull Kind kind) {
        kinds.put(kind.name(), kind);
        if (defaultKind == null) defaultKind = kind.name();
    }

    public static void install(InstanceContainer lobby, Pos lobbySpawn, ShardManager shards) {
        grotto = Grotto.install(shards); // shared manager: /shard tree shows the game trees
        lobbyRoute = player -> {
            player.setGameMode(GameMode.SURVIVAL);
            player.setFlying(false);
            player.setInstance(lobby, lobbySpawn);
            restoreKit(player);
        };
        grotto.exit(lobbyRoute);
        MinecraftServer.getGlobalEventHandler().addListener(PlayerDisconnectEvent.class, e -> {
            UUID gone = e.getPlayer().getUuid();
            kits.remove(gone); // a fresh join re-kits anyway
            requests.remove(gone);
            requests.values().removeIf(request -> request.target().equals(gone));
        });
        register();
    }

    private static void register() {
        Command duel = new Command("duel");
        var name = ArgumentType.Word("player");
        var mode = ArgumentType.Word("mode");
        var look = ArgumentType.Word("look");
        duel.setDefaultExecutor((sender, ctx) ->
                Msg.tag(sender, "duel", "usage: ", Msg.run("/duel <player> [" + String.join("|", kinds.keySet())
                        + "] [" + String.join("|", looks.keySet()) + "]")));
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), defaultKind, null), name);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), null), name, mode);
        duel.addSyntax((sender, ctx) -> request(sender, ctx.get(name), ctx.get(mode), ctx.get(look)), name, mode, look);
        MinecraftServer.getCommandManager().register(duel);
    }

    private static void request(Object sender, String targetName, String kindName, @Nullable String lookName) {
        if (!(sender instanceof Player me)) return;
        Kind kind = kinds.get(kindName);
        if (kind == null) {
            Msg.err(me, "duel", "modes: ", Msg.v(String.join(", ", kinds.keySet())));
            return;
        }
        if (lookName != null && !looks.containsKey(lookName)) {
            Msg.err(me, "duel", "looks: ", Msg.v(String.join(", ", looks.keySet())));
            return;
        }
        Player target = MinecraftServer.getConnectionManager().getOnlinePlayerByUsername(targetName);
        if (target == null) {
            Msg.err(me, "duel", "no player named ", Msg.v(targetName));
            return;
        }
        if (target == me) {
            Msg.err(me, "duel", "you can't duel yourself");
            return;
        }
        if (Game.of(me) != null) {
            Msg.err(me, "duel", "you're already in a duel");
            return;
        }
        if (Game.of(target) != null) {
            Msg.err(me, "duel", Msg.v(target.getUsername()), " is already in a duel");
            return;
        }
        Request theirs = requests.get(target.getUuid());
        if (theirs != null && theirs.expired()) {
            requests.remove(target.getUuid());
            theirs = null;
        }
        if (theirs != null && theirs.target().equals(me.getUuid())) { // accepting: the requester's mode wins
            requests.remove(target.getUuid());
            requests.remove(me.getUuid());
            start(kinds.get(theirs.kind()), theirs.look(), target, me);
            return;
        }
        requests.put(me.getUuid(), new Request(target.getUuid(), kindName, lookName, System.currentTimeMillis()));
        Msg.tag(me, "duel", kindName + " request sent to ", Msg.v(target.getUsername()));
        Msg.tag(target, "duel", Msg.v(me.getUsername()), " wants to " + kindName + " - ",
                Msg.run("/duel " + me.getUsername()), " to accept");
    }

    private static void start(Kind kind, @Nullable String look, Player a, Player b) {
        // the look stamps BEFORE modules install - a Choice board picks its variant at install time
        Game game = grotto.launch(kind.type(), kind.arena().get(), null,
                g -> { if (look != null) g.state().set(Scope.GAME, LOOK, look); });
        kind.onLaunch().accept(game);
        for (Player p : new Player[]{a, b}) {
            stashKit(p);
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth((float) p.getAttributeValue(Attribute.MAX_HEALTH));
            p.setFood(20);
            game.join(p);
        }
        // name the active look: the REQUESTER's mode and look win an accept, which is invisible otherwise
        String tag = kind.name() + (look != null ? " (" + look + ")" : "");
        Msg.tag(a, "duel", tag + " vs ", Msg.v(b.getUsername()));
        Msg.tag(b, "duel", tag + " vs ", Msg.v(a.getUsername()));
    }

    /**
     * Announces the death, shows the verdicts, ghosts the loser (kinds that want it), and ends after a
     * 3s linger. {@code verb} reads {@code "<loser> <verb> <winner>!"} ("was knocked out of the ring by");
     * {@code null} = a forfeit with no killer.
     */
    /** Ends with NO kill line - score and time endings speak through the verdict and end screen. */
    public static void settle(Game game, Player loser) {
        settle(game, loser, null, false);
    }

    public static void settle(Game game, Player loser, @Nullable String verb) {
        settle(game, loser, verb, true);
    }

    private static void settle(Game game, Player loser, @Nullable String verb, boolean announce) {
        GameState st = game.state();
        if ("done".equals(st.get(Scope.GAME, PHASE))) return;
        st.set(Scope.GAME, PHASE, "done");
        st.set(Scope.GAME, DONE_AT, st.tick());
        Player winner = game.shard().members().stream().filter(m -> m != loser).findFirst().orElse(null);
        Kind kind = kindOf(game);
        Look look = lookOf(game); // the duel-time override wins here too
        if (announce) {
            Component death = look.death().render(game, loser, winner, verb);
            each(game, p -> p.sendMessage(death));
        }
        if (winner != null) {
            each(game, p -> look.verdict().show(game, p, p != loser, winner));
        }
        if (kind == null || kind.ghostLoser()) {
            // everyone watching (spectators included) sees a vanilla death: pose now, and the ghosting's
            // destroy packet lands at +20 ticks - right when a real corpse would poof. The loser gets no
            // status 3, only the flight.
            for (Player watcher : game.shard().watchers()) {
                if (watcher != loser) watcher.sendPacket(new EntityStatusPacket(loser.getEntityId(), (byte) 3));
            }
            loser.setFlying(true); // no mayfly: hovering, can't toggle - and a ring-out ghost stops mid-fall
            st.alarms().arm(Countdown.GHOST_ALARM, st.tick() + 20,
                    CompoundBinaryTag.builder().putString("uuid", loser.getUuid().toString()).build());
        }
        Slot seat = winner != null ? game.roster().slotOf(winner) : null;
        game.decide(seat != null ? Outcome.winner(seat.team()) : Outcome.draw(), 60);
    }

    private static void minemenVerdict(Game game, Player viewer, boolean won, Player winner) {
        NamedTextColor tone = won ? NamedTextColor.GREEN : NamedTextColor.RED;
        viewer.showTitle(Title.title(
                Component.text(won ? "VICTORY!" : "DEFEATED!", tone, TextDecoration.BOLD),
                Component.text(winner.getUsername(), tone)
                        .append(Component.text(" won the Match!", NamedTextColor.WHITE)),
                times(game, 0, 40, 10)));
    }

    // captured: [gold]"IgnasSk"[gray]" was killed by "[aqua]"Dogln"[gray]"." - names are rank-colored on
    // hypixel and always "was killed by", whatever actually happened; aqua names here (no ranks)
    private static Component hypixelDeath(Game game, Player loser, @Nullable Player winner, @Nullable String verb) {
        Component name = Component.text(loser.getUsername(), NamedTextColor.AQUA);
        if (winner == null || verb == null) return name.append(Component.text(" forfeited.", NamedTextColor.GRAY));
        return name.append(Component.text(" was killed by ", NamedTextColor.GRAY))
                .append(Component.text(winner.getUsername(), NamedTextColor.AQUA))
                .append(Component.text(".", NamedTextColor.GRAY));
    }

    // captured: yellow prose, red digit, "1 second!" singular; the intro block lands at zero
    private static Component hypixelNumber(int n) {
        return Component.text("The game starts in ", NamedTextColor.YELLOW)
                .append(Component.text(n, NamedTextColor.RED))
                .append(Component.text(n == 1 ? " second!" : " seconds!", NamedTextColor.YELLOW));
    }

    private static @Nullable Component hypixelStarted(Game game, Player viewer) {
        Kind kind = kindOf(game);
        if (kind == null || kind.intro().isEmpty()) return null;
        String mode = Character.toUpperCase(kind.name().charAt(0)) + kind.name().substring(1) + " Duel";
        HypixelIntro intro = HypixelIntro.of(mode);
        kind.intro().forEach(intro::line);
        Player foe = game.members().stream().filter(m -> m != viewer).findFirst().orElse(null);
        return intro.opponent(foe != null ? foe.getUsername() : null).render();
    }

    // captured: gray "Cages open in ", the count green at 5-4, yellow 3-2, red at 1; silent at zero
    private static Component scrimsNumber(int n) {
        NamedTextColor tone = n >= 4 ? NamedTextColor.GREEN : n >= 2 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text("Cages open in ", NamedTextColor.GRAY).append(Component.text(n + "s", tone));
    }

    // captured: a scoring kind shows both players "<TEAM> WINS!" over the bold final score; a plain duel
    // shows DEFEAT! (VICTORY! inferred green - only the losing side was captured) over "<name> won the Duel!"
    private static void hypixelVerdict(Game game, Player viewer, boolean won, Player winner) {
        Kind kind = kindOf(game);
        Title.Times times = times(game, 0, 40, 10);
        if (kind != null && kind.teamScore() != null) {
            Slot seat = game.roster().slotOf(winner);
            Team team = game.roster().team(seat != null ? seat.team() : null);
            List<Team> teams = new java.util.ArrayList<>(game.roster().teams());
            teams.sort(java.util.Comparator.comparing(t -> t.id().equals(team.id()) ? 0 : 1));
            Component score = Component.empty();
            for (int i = 0; i < teams.size(); i++) {
                Team t = teams.get(i);
                if (i > 0) score = score.append(Component.text(" - ", NamedTextColor.GRAY));
                score = score.append(Component.text(game.state().get(Scope.team(t.id()), kind.teamScore()),
                        t.color(), TextDecoration.BOLD));
            }
            viewer.showTitle(Title.title(
                    Component.text(team.id().toUpperCase(java.util.Locale.ROOT) + " WINS!",
                            team.color(), TextDecoration.BOLD),
                    score, times));
            return;
        }
        NamedTextColor tone = won ? NamedTextColor.GREEN : NamedTextColor.RED;
        viewer.showTitle(Title.title(
                Component.text(won ? "VICTORY!" : "DEFEAT!", tone, TextDecoration.BOLD),
                Component.text(winner.getUsername(), NamedTextColor.AQUA)
                        .append(Component.text(" won the Duel!", NamedTextColor.WHITE)),
                times));
    }

    // scrims: winners "Victory!" gold, losers "Defeat" light red - plain, no subtitle
    private static void scrimsVerdict(Game game, Player viewer, boolean won, Player winner) {
        viewer.showTitle(Title.title(
                won ? Component.text("Victory!", NamedTextColor.GOLD) : Component.text("Defeat", NamedTextColor.RED),
                Component.empty(),
                times(game, 0, 40, 10)));
    }

    // captured: [red]"Dogln "[gray]"fell into the void." - team-colored names, gray prose, a period
    private static Component scrimsDeath(Game game, Player loser, @Nullable Player winner, @Nullable String verb) {
        Component line = Component.text(loser.getUsername() + " ", teamColor(game, loser));
        if (verb == null) return line.append(Msg.SCRIMS.prose("forfeited."));
        if (winner == null) return line.append(Msg.SCRIMS.prose(verb));
        return line.append(Msg.SCRIMS.prose(verb + " "))
                .append(Component.text(winner.getUsername(), teamColor(game, winner)))
                .append(Msg.SCRIMS.prose("."));
    }

    public static TextColor teamColor(Game game, Player player) {
        Slot seat = game.roster().slotOf(player);
        return game.roster().team(seat != null ? seat.team() : null).color();
    }

    // game.members(), not shard members: ghosts still get announcements and end-of-game cleanup
    public static void each(Game game, Consumer<Player> action) {
        for (Player p : game.members()) action.accept(p);
    }

    /** Title times in GAME ticks, stretched to wall clock so a rate-scaled game's titles track its beats. */
    public static Title.Times times(Game game, int fadeIn, int stay, int fadeOut) {
        int rate = game.tickRate();
        if (rate == net.minestom.server.ServerFlag.SERVER_TICKS_PER_SECOND) return Titles.times(fadeIn, stay, fadeOut);
        double ms = 1000.0 / rate;
        return Title.Times.times(java.time.Duration.ofMillis(Math.round(fadeIn * ms)),
                java.time.Duration.ofMillis(Math.round(stay * ms)),
                java.time.Duration.ofMillis(Math.round(fadeOut * ms)));
    }

    /** Plays the look's sound for {@code cue} to everyone in the game; a look without one stays silent. */
    public static void cue(Game game, String cue) {
        var sound = lookOf(game).sounds().get(cue);
        if (sound != null) each(game, p -> p.playSound(sound));
    }

    /** The per-look module choice every game repeats - picked at install off the stamped look. */
    public static GameModule byLook(Supplier<GameModule> hypixel, Supplier<GameModule> minemen,
                                    Supplier<GameModule> scrims) {
        return io.github.term4.grotto.modules.Choice.of(g -> switch (lookNameOf(g)) {
            case "hypixel" -> hypixel;
            case "scrims" -> scrims;
            default -> minemen;
        });
    }

    // the MineMen line shape - themed names, themed prose, an exclamation - on the game look's palette
    private static Component minemenDeath(Game game, Player loser, @Nullable Player winner, @Nullable String verb) {
        var theme = lookOf(game).theme();
        if (winner == null || verb == null) {
            return theme.value(loser.getUsername()).append(theme.prose(" forfeited!"));
        }
        return theme.value(loser.getUsername())
                .append(theme.prose(" " + verb + " ")).append(theme.value(winner.getUsername()))
                .append(theme.prose("!"));
    }

    /**
     * A duel world's damage member: every non-melee type switched off, over {@code base} (else the global's
     * damage member) - a world member REPLACES the outer scope's wholesale, so the switches must ride on the
     * config they displace or the preset tuning drops to the vanilla floor. The melee entry is untouched.
     */
    public static DamageConfig meleeOnlyOver(DamageConfig base) {
        if (base == null) {
            MechanicsProfile global = Mechanics.polyp.profiles().global();
            base = global != null ? global.get(MechanicsKeys.DAMAGE) : null;
        }
        DamageConfig.Builder damage = DamageConfig.builder();
        for (Key key : List.of(FallDamage.KEY, CactusDamage.KEY, BurningDamage.KEY, InFireDamage.KEY,
                LavaDamage.KEY, DrowningDamage.KEY, SuffocationDamage.KEY, StarvationDamage.KEY,
                ProjectileDamage.KEY, MagicDamage.KEY, ExplosionDamage.KEY, GenericDamage.KEY)) {
            damage.typeConfig(DamageTypeConfig.builder(key).enabled(false).build());
        }
        DamageConfig switches = damage.build();
        return base != null ? switches.fromBase(base) : switches;
    }

    private static void stashKit(Player player) {
        var inv = player.getInventory();
        ItemStack[] kit = new ItemStack[inv.getSize()];
        for (int i = 0; i < kit.length; i++) kit[i] = inv.getItemStack(i);
        kits.putIfAbsent(player.getUuid(), kit);
        inv.clear();
    }

    private static void restoreKit(Player player) {
        ItemStack[] kit = kits.remove(player.getUuid());
        if (kit == null) return;
        var inv = player.getInventory();
        inv.clear();
        for (int i = 0; i < kit.length; i++) inv.setItemStack(i, kit[i]);
    }

    /** 5-1 with both players frozen (moves cancelled, knockback swallowed), the look voicing the beats. */
    public static final class Countdown implements GameModule {

        static final String GHOST_ALARM = "duel:ghost";

        @Override
        public void install(@NotNull Game game) {
            GameState st = game.state();
            st.alarms().register(GHOST_ALARM, (tick, payload) -> {
                UUID uuid = UUID.fromString(payload.getString("uuid"));
                for (Player p : game.members()) {
                    if (p.getUuid().equals(uuid)) {
                        game.ghost(p);
                        return;
                    }
                }
            });
            st.alarms().register("duel:count", (tick, payload) -> {
                if (!"countdown".equals(st.get(Scope.GAME, PHASE))) return; // settled early (a quit)
                int n = payload.getInt("n");
                if (n > 0) {
                    Look look = lookOf(game);
                    Component line = look.count().number().apply(n);
                    // captured beat title: fadeIn 0, stay 20, fadeOut 20
                    Title title = look.count().title() != null ? Title.title(look.count().title().apply(n),
                            Component.empty(),
                            times(game, 0, 20, 20)) : null;
                    each(game, p -> {
                        p.sendMessage(line);
                        if (title != null) p.showTitle(title);
                        look.sounds().play(p, SoundSet.COUNTDOWN);
                    });
                    st.alarms().arm("duel:count", tick + 20, CompoundBinaryTag.builder().putInt("n", n - 1).build());
                } else if (game.shard().members().size() < 2) { // a duelist never arrived (logged out mid-transfer)
                    st.set(Scope.GAME, PHASE, "done");
                    each(game, p -> Msg.tag(p, "duel", "duel cancelled - your opponent never showed"));
                    game.decide(Outcome.draw(), 1);
                } else {
                    st.set(Scope.GAME, PHASE, "fighting");
                    Look look = lookOf(game);
                    // captured: the beat title fades out empty (10, 0, 10)
                    Title clear = look.count().title() != null ? Title.title(Component.empty(), Component.empty(),
                            times(game, 10, 0, 10)) : null;
                    each(game, p -> {
                        Component started = look.count().started().apply(game, p);
                        if (started != null) p.sendMessage(started);
                        if (clear != null) p.showTitle(clear);
                        look.sounds().play(p, SoundSet.START);
                    });
                }
            });
            st.alarms().arm("duel:count", st.tick() + 20, CompoundBinaryTag.builder().putInt("n", 5).build());

            // cancelling would resync with ABSOLUTE view (the client's look rewinds mid-flick); instead let
            // the packet land, then pin back to the seat with yaw/pitch as zero RELATIVE deltas
            Set<UUID> pinning = new HashSet<>();
            game.on(PlayerMoveEvent.class, e -> {
                if (!"countdown".equals(st.get(Scope.GAME, PHASE))) return;
                Player p = e.getPlayer();
                Pos from = p.getPosition();
                Pos to = e.getNewPosition();
                if (from.x() == to.x() && from.y() == to.y() && from.z() == to.z()) return;
                Slot seat = game.roster().slotOf(p);
                Pos anchor = seat != null && seat.spawn() != null ? seat.spawn() : from;
                if (!pinning.add(p.getUuid())) return;
                MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> {
                    pinning.remove(p.getUuid());
                    if (!"countdown".equals(st.get(Scope.GAME, PHASE))) return;
                    p.teleport(anchor.withView(0, 0), null, RelativeFlags.VIEW);
                });
            });
            game.on(KnockbackEvent.class, KnockbackEvent::target, e -> {
                String phase = st.get(Scope.GAME, PHASE);
                if ("fighting".equals(phase)) return;
                // the settling tick's KB is the final hit - it lands; anything later in "done" is swallowed
                if ("done".equals(phase) && st.tick() == st.get(Scope.GAME, DONE_AT)) return;
                e.setCancelled(true);
            });
        }
    }
}
