package tennacle.games.bedwars;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.GameModule;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.PlayerPrefs;
import tennacle.games.KitLayouts;
import tennacle.games.Match;
import tennacle.games.beds.Beds;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Each player's Bed Wars hotbar as picks in order, slot by slot: a kind ({@code utility}) or one shop item
 * ({@code ware:fireball}). An optional second layout takes over once their bed is gone, the hotbar rearranged at once.
 */
final class HotbarOrders {

    static final int SLOTS = 9, PICKS = 9;
    static final String WARE = "ware:";

    /** {@code afterBed} empty = the main layout all game. */
    record Layouts(@NotNull List<List<String>> main, @NotNull List<List<String>> afterBed) {
        static final Codec<Layouts> CODEC = StructCodec.struct(
                "main", Codec.STRING.list().list(), Layouts::main,
                "after", Codec.STRING.list().list(), Layouts::afterBed,
                Layouts::new);
        static final Layouts UNSET = new Layouts(List.of(), List.of());

        boolean splits() {
            return afterBed.stream().anyMatch(slot -> !slot.isEmpty());
        }
    }

    static final PlayerPrefs.Entry<Layouts, Layouts> STORE =
            PlayerPrefs.register(VaultKey.of("bedwars:hotbar", Layouts.CODEC, Layouts.UNSET));

    private HotbarOrders() {}

    /** The layout the kit routes by: the after-bed one when the player has one and their bed is gone. */
    static @Nullable List<List<String>> orders(Game game, Player player) {
        Layouts mine = of(player);
        String team = Match.teamOf(game, player);
        if (mine.splits() && team != null && !Beds.stands(game, team)) return mine.afterBed();
        return mine.main();
    }

    static @NotNull Layouts of(@NotNull Player player) {
        Layouts held = STORE.of(player.getUuid());
        return held != null && !held.main().isEmpty() ? held : new Layouts(fromLayout(player), List.of());
    }

    static void set(@NotNull Player player, @NotNull Layouts layouts) {
        STORE.set(player, layouts);
    }

    /** A blank layout: nine empty slots. */
    static @NotNull List<List<String>> empty() {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) out.add(List.of());
        return out;
    }

    /** The kit's own slots, each name first in its slot. */
    static @NotNull List<List<String>> defaults() {
        return firsts(BedWars.KIT.defaultSlots());
    }

    // what the one-slot-per-kind Hotbar Manager saved, each kind first in its slot; else the kit's own slots
    private static List<List<String>> fromLayout(Player player) {
        Map<String, Integer> saved = KitLayouts.of(player, BedWars.KIT.id());
        return saved != null && !saved.isEmpty() ? firsts(saved) : defaults();
    }

    private static List<List<String>> firsts(Map<String, Integer> slots) {
        List<List<String>> out = new ArrayList<>(empty());
        slots.forEach((kind, slot) -> {
            if (slot >= 0 && slot < SLOTS) out.set(slot, List.of(kind));
        });
        return out;
    }

    /** Rearranges each member of a side whose bed just fell and who keeps a second layout. */
    static final class Switch implements GameModule {
        @Override
        public void install(@NotNull Game game) {
            Beds beds = game.module(Beds.class);
            if (beds == null) throw new IllegalStateException("the after-bed hotbar needs Beds installed before it");
            beds.onFall(team -> game.forEachMember(player -> {
                if (!team.equals(Match.teamOf(game, player)) || !of(player).splits()) return;
                Kits kits = game.module(Kits.class);
                if (kits != null) kits.rearrange(player);
            }));
        }
    }
}
