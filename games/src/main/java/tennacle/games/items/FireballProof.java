package tennacle.games.items;

import net.kyori.adventure.key.Key;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dropped items a fireball leaves whole, as a season writes it: {@code none}, or materials with an optional end,
 * {@code diamond}, {@code diamond emerald until beds 1}. TNT still takes them.
 */
public record FireballProof(@NotNull Set<Material> materials, @NotNull List<Unlock.Condition> until) {

    public static final FireballProof NONE = new FireballProof(Set.of(), List.of());

    public static @NotNull FireballProof parse(@NotNull String text) {
        String spec = text.trim().toLowerCase(Locale.ROOT);
        if (spec.isEmpty() || spec.equals("none")) return NONE;
        String[] halves = spec.split("\\s+until\\s+", 2);
        Set<Material> materials = new LinkedHashSet<>();
        for (String name : halves[0].split("[\\s,]+")) {
            Material material = Key.parseable(name) ? Material.fromKey(name) : null;
            if (material == null) throw new IllegalArgumentException("not an item: \"" + name + "\" in \"" + text + "\"");
            materials.add(material);
        }
        List<Unlock.Condition> until = halves.length == 2 ? Unlock.parse(halves[1]).anyOf() : List.of();
        if (halves.length == 2 && until.isEmpty()) throw new IllegalArgumentException("until what? \"" + text + "\"");
        return new FireballProof(Collections.unmodifiableSet(materials), until);
    }

    @Override
    public @NotNull String toString() {
        if (materials.isEmpty()) return "none";
        List<String> names = new ArrayList<>();
        for (Material material : materials) names.add(material.key().value());
        String head = String.join(" ", names);
        return until.isEmpty() ? head : head + " until " + new Unlock(false, until);
    }
}
