package tennacle.games;

import io.github.term4.canvas.theme.Livery;
import io.github.term4.canvas.theme.Tint;
import io.github.term4.grotto.Game;
import io.github.term4.grotto.event.PhaseChangeEvent;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.modules.gear.Kits;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.MinecraftServer;
import net.minestom.server.codec.Codec;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.Player;
import net.minestom.server.event.EventListener;
import net.minestom.server.event.inventory.InventoryCloseEvent;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.event.item.ItemDropEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSwapItemEvent;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.tag.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tennacle.Msg;
import tennacle.PlayerPrefs;
import tennacle.commands.AppCommand;
import tennacle.commands.Commands;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-player kit slot arrangements: one vault value for all kits, prefetched through {@link PlayerPrefs},
 * kit-default fallback on anything missing or malformed. The editor puts the kit in your own inventory, a random
 * team's colors on whatever takes them; arrange it and close the inventory to save. Armor stays on unless the kit
 * lets it move. Rearranging during a game's first countdown saves too, for the kits that learn.
 */
public final class KitLayouts {

    // one value for all kits: "bridge>sword:0,bow:1|other>..." - room to grow without new keys
    private static final VaultKey<String> LAYOUTS = VaultKey.of("kits:layouts", Codec.STRING, "");
    private static final PlayerPrefs.Entry<String, Map<String, Map<String, Integer>>> STORE =
            PlayerPrefs.register(LAYOUTS, KitLayouts::parse, KitLayouts::serialize);
    private static final Tag<String> ROLE = Tag.String("tennacle:kit-role");
    private static final Tag<Long> TOLD = Tag.Transient("tennacle:kit-told");
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE,
            EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};

    /** A kit the editor arranges; {@code learns} = a game's first countdown saves how it was left. */
    public record Editable(@NotNull String id, @NotNull String title, @NotNull Kit kit, boolean learns) {}

    private static final Map<String, Editable> EDITABLE = new LinkedHashMap<>();
    private static final Map<UUID, Edit> EDITING = new ConcurrentHashMap<>();
    private static final Set<Game> STARTED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile boolean wired;

    private record Edit(Editable editable, ItemStack[] stash) {}

    private KitLayouts() {}

    /** The stored remap for {@code kitId}, or null = the kit's defaults. */
    public static @Nullable Map<String, Integer> of(Player player, String kitId) {
        Map<String, Map<String, Integer>> kits = STORE.of(player.getUuid());
        return kits != null ? kits.get(kitId) : null;
    }

    /** Stores {@code layout} as {@code player}'s for {@code kitId}. */
    public static void set(@NotNull Player player, @NotNull String kitId, @NotNull Map<String, Integer> layout) {
        Map<String, Map<String, Integer>> kits = STORE.of(player.getUuid());
        Map<String, Map<String, Integer>> next = kits != null ? new ConcurrentHashMap<>(kits) : new ConcurrentHashMap<>();
        next.put(kitId, Map.copyOf(layout));
        STORE.set(player, next);
    }

    public static @NotNull List<Editable> editables() {
        return List.copyOf(EDITABLE.values());
    }

    public static boolean editing(@NotNull Player player) {
        return EDITING.containsKey(player.getUuid());
    }

    /** Registers {@code kit} with the editor, learning from first countdowns. */
    public static void editable(@NotNull String title, @NotNull Kit kit) {
        editable(title, kit, true);
    }

    public static void editable(@NotNull String title, @NotNull Kit kit, boolean learns) {
        EDITABLE.put(kit.id(), new Editable(kit.id(), title, kit, learns));
        if (wired) return;
        wired = true;
        wire();
    }

    private static void wire() {
        var events = MinecraftServer.getGlobalEventHandler();
        events.addListener(InventoryCloseEvent.class, e -> {
            if (e.getInventory() != e.getPlayer().getInventory()) return;
            Edit edit = EDITING.remove(e.getPlayer().getUuid());
            if (edit != null) finish(e.getPlayer(), edit);
        });
        events.addListener(PlayerDisconnectEvent.class, e -> EDITING.remove(e.getPlayer().getUuid())); // the lobby rebuilds next login
        events.addListener(InventoryPreClickEvent.class, e -> {
            Player player = e.getPlayer();
            Edit edit = EDITING.get(player.getUuid());
            if (edit == null || e.getInventory() != player.getInventory() || edit.editable().kit().armorMoves()) return;
            if (!touchesArmor(e.getClick())) return;
            e.setCancelled(true);
            tell(player, "This kit's armor stays on.");
        });
        // observed past a cancel: a lobby with tossing off has already said no, the message is still ours
        events.addListener(EventListener.builder(ItemDropEvent.class).ignoreCancelled(false).handler(e -> {
            if (!editing(e.getPlayer())) return;
            e.setCancelled(true);
            tell(e.getPlayer(), "You can't drop items while arranging your kit.");
        }).build());
        events.addListener(PlayerUseItemEvent.class, e -> {
            if (!editing(e.getPlayer())) return;
            e.setCancelled(true);
            tell(e.getPlayer(), "You can't use items while arranging your kit.");
        });
        events.addListener(PlayerSwapItemEvent.class, e -> {
            if (editing(e.getPlayer())) e.setCancelled(true);
        });
        events.addListener(PhaseChangeEvent.class, e -> {
            if (e.from().equals(Match.OPENING) && e.to().equals(Match.LIVE) && STARTED.add(e.game())) learn(e.game());
        });

        AppCommand cmd = Commands.player("kit", "Arrange a mode's kit your way.")
                .usage("/kit <mode>", "the kit lands in your inventory; close it to save");
        var kitArg = ArgumentType.Word("mode");
        kitArg.setSuggestionCallback(tennacle.commands.Completions.of(EDITABLE::keySet));
        cmd.setDefaultExecutor((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            Msg.say(p, "Kits you can arrange: ", Msg.v(String.join(", ", EDITABLE.keySet())),
                    ". Try ", Msg.v("/kit " + EDITABLE.keySet().iterator().next()), ".");
        });
        cmd.addSyntax((sender, ctx) -> {
            if (!(sender instanceof Player p)) return;
            String refused = open(p, ctx.get(kitArg));
            if (refused != null) Msg.fail(p, refused);
        }, kitArg);
        Commands.register(cmd);
    }

    /** Drops an open edit and hands the body back, saving nothing - a seat is not the place to be editing. */
    public static void cancel(@NotNull Player player) {
        Edit edit = EDITING.remove(player.getUuid());
        if (edit == null) return;
        restore(player, edit);
        Msg.fail(player, "The kit edit closed unsaved: you joined a game.");
    }

    /** Puts {@code kitId}'s kit in {@code player}'s inventory to arrange; why not, else null. */
    public static @Nullable String open(@NotNull Player player, @NotNull String kitId) {
        Editable editable = EDITABLE.get(kitId);
        if (editable == null) return "No kit to arrange for " + kitId + ". Kits: " + String.join(", ", EDITABLE.keySet()) + ".";
        if (Game.of(player) != null) return "Not mid-game. Edit kits from the lobby.";
        if (editing(player)) return "You're already arranging a kit. Close your inventory to save.";
        Kit kit = editable.kit();
        var inv = player.getInventory();
        ItemStack[] stash = new ItemStack[inv.getSize()];
        for (int i = 0; i < stash.length; i++) stash[i] = inv.getItemStack(i);
        inv.clear();
        Livery team = Livery.of(randomTint());
        Map<String, Integer> mine = of(player, kitId);
        Map<String, Integer> current = new LinkedHashMap<>(kit.defaultSlots());
        if (mine != null) current.putAll(mine);
        Map<String, ItemStack> displays = kit.displays(team);
        List<ItemStack> homeless = new ArrayList<>();
        current.forEach((role, slot) -> {
            ItemStack shown = displays.get(role);
            if (shown == null) return;
            ItemStack tagged = shown.withTag(ROLE, role);
            if (slot >= 0 && slot <= 35 && inv.getItemStack(slot).isAir()) inv.setItemStack(slot, tagged);
            else homeless.add(tagged);
        });
        kit.armorDisplays(team).forEach((piece, shown) -> {
            String name = Kit.armorName(piece);
            ItemStack tagged = shown.withTag(ROLE, name);
            Integer carried = kit.armorMoves() && mine != null ? mine.get(name) : null;
            if (carried != null && carried >= 0 && carried <= 35 && inv.getItemStack(carried).isAir()) inv.setItemStack(carried, tagged);
            else player.setEquipment(piece, tagged);
        });
        homeless.forEach(inv::addItemStack);
        EDITING.put(player.getUuid(), new Edit(editable, stash));
        Msg.say(player, "Arrange the ", Msg.v(editable.title()), " kit in your inventory, then close it to save.");
        return null;
    }

    private static void finish(Player player, Edit edit) {
        Editable editable = edit.editable();
        Kit kit = editable.kit();
        var inv = player.getInventory();
        Map<String, Integer> found = new LinkedHashMap<>();
        for (int slot = 0; slot <= 35; slot++) {
            String role = inv.getItemStack(slot).getTag(ROLE);
            if (role != null) found.put(role, slot);
        }
        if (kit.armorMoves()) {
            for (EquipmentSlot piece : ARMOR) {
                String role = player.getEquipment(piece).getTag(ROLE);
                if (role != null) found.put(role, Kit.wornAt(piece));
            }
        }
        restore(player, edit);
        set(player, editable.id(), merged(kit, of(player, editable.id()), found));
        Msg.ok(player, "Saved your ", Msg.v(editable.title()), " layout.");
    }

    private static void restore(Player player, Edit edit) {
        var inv = player.getInventory();
        if (inv.getCursorItem().getTag(ROLE) != null) inv.setCursorItem(ItemStack.AIR);
        for (int i = 0; i < edit.stash().length; i++) inv.setItemStack(i, edit.stash()[i]);
        inv.update();
    }

    // the kit's slots, what was stored over them, what was just found over that; a stored name whose slot was taken
    // by something found loses it, so the whole layout never collides
    private static Map<String, Integer> merged(Kit kit, @Nullable Map<String, Integer> before, Map<String, Integer> found) {
        Map<String, Integer> layout = new LinkedHashMap<>(kit.defaultSlots());
        if (before != null) layout.putAll(before);
        Set<Integer> taken = new HashSet<>(found.values());
        layout.replaceAll((role, slot) -> !found.containsKey(role) && taken.contains(slot) ? -1 : slot);
        layout.putAll(found);
        return layout;
    }

    // what each seat left in its hands as the first countdown ran out, for the kits that learn
    private static void learn(Game game) {
        Kits kits = game.module(Kits.class);
        if (kits == null) return;
        for (Player player : game.members()) {
            Slot seat = game.roster().slotOf(player);
            if (seat == null) continue;
            Kit kit = kits.selected(player);
            Editable editable = EDITABLE.get(kit.id());
            if (editable == null || !editable.learns()) continue;
            Map<String, Integer> found = kit.layoutOf(game, player, seat);
            if (found.isEmpty()) continue;
            Map<String, Integer> before = of(player, kit.id());
            Map<String, Integer> layout = merged(kit, before, found);
            if (!layout.equals(merged(kit, before, Map.of()))) set(player, kit.id(), layout);
        }
    }

    private static Tint randomTint() {
        List<Tint> palette = Livery.ordinals().stream().filter(tint -> !tint.isNone()).toList();
        return palette.isEmpty() ? Tint.NONE : palette.get(ThreadLocalRandom.current().nextInt(palette.size()));
    }

    private static boolean touchesArmor(Click click) {
        if (click instanceof Click.Drag drag) return drag.slots().stream().anyMatch(KitLayouts::armor);
        return armor(click.slot());
    }

    private static boolean armor(int slot) {
        return slot >= 41 && slot <= 44;
    }

    // one line a second at most: a held key or a spammed click sends many
    private static void tell(Player player, String line) {
        long now = System.currentTimeMillis();
        Long last = player.getTag(TOLD);
        if (last != null && now - last < 1000) return;
        player.setTag(TOLD, now);
        Msg.fail(player, line);
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
