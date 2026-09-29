package tennacle.games;

import io.github.term4.archipelago.shard.ShardTabList;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.flow.Phases;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.state.Scope;
import io.github.term4.grotto.state.StateKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.PlayerInfoUpdatePacket;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.games.matchmaking.Parties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Strangers' names while a public game fills (Hypixel, off 2024-08-28): every member outside your party reads as a
 * fresh string of letters and digits, gray and obfuscated, in the tab, over the head and in the join and quit lines,
 * until the game opens and the real names take over. You and your party read as yourselves.
 */
public final class HiddenNames implements GameModule {

    /** Hide strangers' names while the game fills. */
    public static final StateKey<Boolean> HIDDEN = StateKey.of("view.names.hidden", Codec.BOOLEAN, false);

    private static final Map<Game, HiddenNames> BY_GAME = Collections.synchronizedMap(new WeakHashMap<>());
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static boolean hooked;

    private final Map<UUID, String> aliases = new ConcurrentHashMap<>();
    private Game game;

    @Override
    public @NotNull Set<StateKey<?>> rules() {
        return Set.of(HIDDEN);
    }

    @Override
    public void install(@NotNull Game game) {
        this.game = game;
        BY_GAME.put(game, this);
        hook();
        Phases phases = game.module(Phases.class);
        if (phases != null) phases.onEnter(Match.LIVE, g -> reveal());
        game.rules().watch(HIDDEN, (scope, value) -> {
            if (!Boolean.TRUE.equals(value)) reveal();
        });
    }

    @Override
    public void onJoin(@NotNull Game game, @NotNull Player player, @NotNull Slot slot, boolean reclaimed) {
        if (!hiding()) return;
        aliases.computeIfAbsent(player.getUuid(), id -> alias());
        // the row and the body went out as the player arrived, before the seat: again, under the alias
        relist(player);
    }

    @Override
    public void onLeave(@NotNull Game game, @NotNull Player player, @NotNull Game.Departure departure) {
        // the quit line still reads the alias this tick
        MinecraftServer.getSchedulerManager().scheduleEndOfTick(() -> aliases.remove(player.getUuid()));
    }

    /** The name {@code viewer} reads for {@code subject}: an alias while the game fills and they are strangers, else null. */
    public static @Nullable String aliasFor(@NotNull Game game, @NotNull Player viewer, @NotNull Player subject) {
        HiddenNames hidden = BY_GAME.get(game);
        return hidden == null ? null : hidden.alias(viewer, subject);
    }

    private @Nullable String alias(Player viewer, Player subject) {
        if (!hiding() || viewer == subject || Parties.together(viewer, subject)) return null;
        return aliases.get(subject.getUuid());
    }

    private boolean hiding() {
        return Match.filling(game) && game.rules().resolve(Scope.GAME, HIDDEN);
    }

    // captured: 5 to 11 letters and digits, a fresh one each
    private static String alias() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int length = 5 + rnd.nextInt(7);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(LETTERS.charAt(rnd.nextInt(LETTERS.length())));
        return out.toString();
    }

    private void reveal() {
        if (aliases.isEmpty()) return;
        List<UUID> shown = new ArrayList<>(aliases.keySet());
        aliases.clear();
        for (UUID id : shown) {
            Player subject = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(id);
            if (subject != null) relist(subject);
        }
    }

    // the row again, then the body: a client keeps the profile its entity spawned with
    private static void relist(Player subject) {
        ShardTabList.resync(subject);
        for (Player viewer : new ArrayList<>(subject.getViewers())) {
            if (viewer == subject) continue;
            subject.updateOldViewer(viewer);
            subject.updateNewViewer(viewer);
        }
    }

    private static synchronized void hook() {
        if (hooked) return;
        hooked = true;
        ShardTabList.entries(HiddenNames::aliased);
    }

    // the tab row under the alias: the name the client spawns the body with, and the gray obfuscated display
    private static PlayerInfoUpdatePacket.Entry aliased(Player viewer, Player subject, PlayerInfoUpdatePacket.Entry entry) {
        Game game = Game.of(viewer);
        HiddenNames hidden = game != null ? BY_GAME.get(game) : null;
        String alias = hidden != null ? hidden.alias(viewer, subject) : null;
        if (alias == null) return entry;
        return new PlayerInfoUpdatePacket.Entry(entry.uuid(), alias, entry.properties(), entry.listed(), entry.latency(),
                entry.gameMode(), Component.text(alias, NamedTextColor.GRAY, TextDecoration.OBFUSCATED),
                entry.chatSession(), entry.listOrder(), entry.displayHat());
    }
}
