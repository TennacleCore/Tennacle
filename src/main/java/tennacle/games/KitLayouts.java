package tennacle.games;

import tennacle.Msg;
import tennacle.PlayerPrefs;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player kit slot arrangements: one vault value for all kits, prefetched through {@link PlayerPrefs},
 * kit-default fallback on anything missing or malformed. {@code /kitbeta [kit]} is the bare editor - the
 * kit lands in your inventory, arrange it, close to save. (A Canvas screen replaces the command later;
 * the data layer stays.)
 */
public final class KitLayouts {

    // one value for all kits: "bridge>sword:0,bow:1|other>..." - room to grow without new keys
    private static final VaultKey<String> LAYOUTS = VaultKey.of("kits:layouts", Codec.STRING, "");
    private static final PlayerPrefs.Entry<String, Map<String, Map<String, Integer>>> STORE =
            PlayerPrefs.register(LAYOUTS, KitLayouts::parse, KitLayouts::serialize);
    private static final Tag<String> ROLE = Tag.String("tennacle:kit-role");

    private static final Map<String, Kit> EDITABLE = new LinkedHashMap<>();
    private static final Map<UUID, Edit> EDITING = new ConcurrentHashMap<>();
    private static volatile boolean wired;

    private record Edit(String kitId, Kit kit, ItemStack[] stash) {}

    private KitLayouts() {}

    /** The stored remap for {@code kitId}, or null = the kit's defaults. */
    public static @Nullable Map<String, Integer> of(Player player, String kitId) {
        Map<String, Map<String, Integer>> kits = STORE.of(player.getUuid());
        return kits != null ? kits.get(kitId) : null;
    }

    /** Registers {@code kit} with the editor; the first call wires the close-to-save hook and the command. */
    public static void editable(String kitId, Kit kit) {
        EDITABLE.put(kitId, kit);
        if (wired) return;
        wired = true;
        MinecraftServer.getGlobalEventHandler().addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() != e.getPlayer().getInventory()) return;
            Edit edit = EDITING.remove(e.getPlayer().getUuid());
            if (edit != null) finish(e.getPlayer(), edit);
        });
        MinecraftServer.getGlobalEventHandler().addListener(
                net.minestom.server.event.player.PlayerDisconnectEvent.class,
                e -> EDITING.remove(e.getPlayer().getUuid())); // the lobby flow rebuilds next login

        Command cmd = new Command("kitbeta");
        var kitArg = ArgumentType.Word("kit");
        cmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            open(p, EDITABLE.keySet().iterator().next());
        });
        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            open(p, ctx.get(kitArg));
        }, kitArg);
        MinecraftServer.getCommandManager().register(cmd);
    }

    private static void open(Player p, String kitId) {
        Kit kit = EDITABLE.get(kitId);
        if (kit == null) {
            Msg.err(p, "kit", "no editable kit '" + kitId + "' (" + String.join(", ", EDITABLE.keySet()) + ")");
            return;
        }
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
        Map<String, Integer> current = new LinkedHashMap<>(kit.defaultSlots());
        Map<String, Integer> mine = of(p, kitId);
        if (mine != null) current.putAll(mine);
        Map<String, ItemStack> displays = kit.displays();
        current.forEach((role, slot) ->
                inv.setItemStack(slot, displays.getOrDefault(role, ItemStack.AIR).withTag(ROLE, role)));
        EDITING.put(p.getUuid(), new Edit(kitId, kit, stash));
        Msg.tag(p, "kit", "arrange the " + kitId + " kit, then close your inventory to save");
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
        Map<String, Map<String, Integer>> kits = STORE.of(player.getUuid());
        Map<String, Map<String, Integer>> next =
                kits != null ? new ConcurrentHashMap<>(kits) : new ConcurrentHashMap<>();
        next.put(edit.kitId(), layout);
        STORE.set(player, next);
        Msg.tag(player, "kit", edit.kitId() + " layout saved");
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
