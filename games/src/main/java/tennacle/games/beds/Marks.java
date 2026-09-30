package tennacle.games.beds;

import tennacle.games.watch.Remote;

import io.github.term4.archipelago.replay.Replay;
import io.github.term4.canvas.screen.Items;
import io.github.term4.canvas.theme.Theme;
import io.github.term4.canvas.theme.Tint;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.minestom.server.component.DataComponents;
import net.minestom.server.entity.PlayerSkin;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.network.player.ResolvableProfile;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** What the replay's bookmark screen shows for a bed taken and a death nobody came back from. */
public final class Marks {

    private Marks() {}

    public static @NotNull ItemStack render(@NotNull Replay.Bookmark mark, @NotNull Theme theme) {
        return switch (mark.name()) {
            case "bed" -> bed(mark, theme);
            case "final" -> lastDeath(mark, theme);
            default -> Remote.plainMark(mark);
        };
    }

    // the bed itself, in the color of the side that lost it
    private static ItemStack bed(Replay.Bookmark mark, Theme theme) {
        CompoundBinaryTag data = mark.data();
        TextColor lost = TextColor.color(data.getInt("bedColor"));
        TextColor took = TextColor.color(data.getInt("breakerColor"));
        List<Component> lore = new ArrayList<>();
        lore.add(theme.prose("Broken by ").append(Component.text(data.getString("breaker"), took)));
        lore.add(theme.dim("At " + Remote.stamp(mark.tick())));
        return Items.lore(Items.named(Tint.of(lost).block("bed"),
                Component.text(data.getString("bed") + " Bed", lost)
                        .append(theme.prose(" destroyed"))), lore);
    }

    // the face of whoever it was, with both sides of the kill under it
    private static ItemStack lastDeath(Replay.Bookmark mark, Theme theme) {
        CompoundBinaryTag data = mark.data();
        TextColor fell = TextColor.color(data.getInt("victimColor"));
        String killer = data.getString("killer");
        List<Component> lore = new ArrayList<>();
        lore.add(killer.isEmpty()
                ? theme.prose(data.getString("verb"))
                : theme.prose(data.getString("verb") + " ")
                        .append(Component.text(killer, TextColor.color(data.getInt("killerColor")))));
        lore.add(theme.dim("At " + Remote.stamp(mark.tick())));
        ItemStack head = Items.lore(Items.named(Material.PLAYER_HEAD,
                Component.text(data.getString("victim"), fell)
                        .append(Component.text(" - FINAL KILL", NamedTextColor.AQUA))), lore);
        String textures = data.getString("skin");
        if (textures.isEmpty()) return head;
        String signature = data.getString("skinSig");
        return head.with(DataComponents.PROFILE,
                new ResolvableProfile(new PlayerSkin(textures, signature.isEmpty() ? null : signature)));
    }
}
