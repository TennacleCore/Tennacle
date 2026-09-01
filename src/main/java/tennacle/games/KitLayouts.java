package tennacle.games;

import tennacle.Msg;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.Command;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-player kit slot arrangements: vault-backed, prefetched at login, kit-default fallback on anything
 * missing or malformed. {@code /kitbeta} is the bare editor - the kit lands in your inventory, arrange it,
 * close to save. (The Canvas screen replaces the command later; the data layer stays.)
 */
public final class KitLayouts {

    // one value for all kits: "bridge>sword:0,bow:1|other>..." - room to grow without new keys
    private static final VaultKey<String> LAYOUTS = VaultKey.of("kits:layouts", Codec.STRING, "");
    private static final Tag<String> ROLE = Tag.String("tennacle:kit-role");

    private static final Map<UUID, Map<String, Map<String, Integer>>> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, Edit> EDITING = new ConcurrentHashMap<>();

    private record Edit(String kitId, Kit kit, ItemStack[] stash) {}

    private KitLayouts() {}

    /** The stored remap for {@code kitId}, or null = the kit's defaults. */
    public static @Nullable Map<String, Integer> of(Player player, String kitId) {
        Map<String, Map<String, Integer>> kits = CACHE.get(player.getUuid());
        return kits != null ? kits.get(kitId) : null;
    }

    /** Wire login prefetch, disconnect eviction, the close-to-save hook, and {@code /kitbeta} for {@code kit}. */
    public static void install(String betaKitId, Kit betaKit, Function<String, ItemStack> display) {
        var node = MinecraftServer.getGlobalEventHandler();
        node.addListener(AsyncPlayerConfigurationEvent.class, e -> preload(e.getPlayer().getUuid()));
        node.addListener(PlayerDisconnectEvent.class, e -> {
            CACHE.remove(e.getPlayer().getUuid());
            EDITING.remove(e.getPlayer().getUuid()); // the lobby flow rebuilds the inventory next login
        });
        node.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() != e.getPlayer().getInventory()) return;
            Edit edit = EDITING.remove(e.getPlayer().getUuid());
            if (edit != null) finish(e.getPlayer(), edit);
        });

        Command cmd = new Command("kitbeta");
        cmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            if (Game.of(p) != null) {
                Msg.err(p, "kit", "not mid-game - use the lobby");
                return;
            }
            if (EDITING.containsKey(p.getUuid())) {
                Msg.err(p, "kit", "already editing - close your inventory to save");
                return;
            }
            var inv = p.getInventory();
            ItemStack[] stash = new ItemStack[inv.getSize()];
            for (int i = 0; i < stash.length; i++) stash[i] = inv.getItemStack(i);
            inv.clear();
            Map<String, Integer> current = new LinkedHashMap<>(betaKit.defaultSlots());
            Map<String, Integer> mine = of(p, betaKitId);
            if (mine != null) current.putAll(mine);
            current.forEach((role, slot) ->
                    inv.setItemStack(slot, display.apply(role).withTag(ROLE, role)));
            EDITING.put(p.getUuid(), new Edit(betaKitId, betaKit, stash));
            Msg.tag(p, "kit", "arrange the " + betaKitId + " kit, then close your inventory to save");
        });
        MinecraftServer.getCommandManager().register(cmd);
    }

    private static void finish(Player player, Edit edit) {
        var inv = player.getInventory();
        Map<String, Integer> layout = new LinkedHashMap<>(edit.kit().defaultSlots());
        Map<String, Integer> before = of(player, edit.kitId());
        if (before != null) layout.putAll(before); // a dropped/lost item keeps its old spot
        for (int slot = 0; slot <= 35; slot++) {
            String role = inv.getItemStack(slot).getTag(ROLE);
            if (role != null) layout.put(role, slot);
        }
        for (int i = 0; i < edit.stash().length; i++) inv.setItemStack(i, edit.stash()[i]);
        inv.update();
        CACHE.computeIfAbsent(player.getUuid(), u -> new ConcurrentHashMap<>()).put(edit.kitId(), layout);
        Duels.grotto().vault().set(player.getUuid(), LAYOUTS, serialize(CACHE.get(player.getUuid())));
        Msg.tag(player, "kit", edit.kitId() + " layout saved");
    }

    private static void preload(UUID player) {
        Duels.grotto().vault().get(player, LAYOUTS)
                .thenAccept(stored -> CACHE.put(player, parse(stored)));
    }

    private static String serialize(Map<String, Map<String, Integer>> kits) {
        StringBuilder out = new StringBuilder();
        kits.forEach((kit, layout) -> {
            if (!out.isEmpty()) out.append('|');
            out.append(kit).append('>');
            boolean first = true;
            for (var e : layout.entrySet()) {
                if (!first) out.append(',');
                out.append(e.getKey()).append(':').append(e.getValue());
                first = false;
            }
        });
        return out.toString();
    }

    private static Map<String, Map<String, Integer>> parse(String stored) {
        Map<String, Map<String, Integer>> kits = new ConcurrentHashMap<>();
        if (stored.isEmpty()) return kits;
        try {
            for (String kit : stored.split("\\|")) {
                int at = kit.indexOf('>');
                if (at <= 0) continue;
                Map<String, Integer> layout = new HashMap<>();
                for (String pair : kit.substring(at + 1).split(",")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0) layout.put(pair.substring(0, colon), Integer.parseInt(pair.substring(colon + 1)));
                }
                kits.put(kit.substring(0, at), layout);
            }
        } catch (RuntimeException malformed) {
            return new ConcurrentHashMap<>(); // fallback to defaults rather than a half-read store
        }
        return kits;
    }
}
