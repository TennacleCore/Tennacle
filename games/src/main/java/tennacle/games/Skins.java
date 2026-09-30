package tennacle.games;

import tennacle.PlayerPrefs;

import io.github.term4.grotto.Game;
import io.github.term4.grotto.kit.Kit;
import io.github.term4.grotto.roster.Slot;
import io.github.term4.grotto.vault.VaultKey;
import net.minestom.server.codec.Codec;
import net.minestom.server.entity.Player;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Families of items that play the same (every plank and log is wood), each player wearing the face they picked:
 * whatever of the family comes to their hands takes it, and whatever leaves them, dropped, spilled or broken out
 * of a block, is the family's plain face again. A mode registers its families and hands its kit {@link #skin()}.
 */
public final class Skins {

    /** {@code plain} is what the family is when nobody wears it, and a face any of {@code faces}. */
    public record Family(@NotNull String id, @NotNull Material plain, @NotNull Set<Material> faces) {
        public Family {
            faces = Set.copyOf(faces);
            if (!faces.contains(plain)) throw new IllegalArgumentException(id + "'s plain face is not one of its faces");
        }
    }

    private static final VaultKey<Map<String, String>> KEY =
            VaultKey.of("skins", Codec.STRING.mapValue(Codec.STRING), Map.of());
    private static final Map<String, Family> FAMILIES = new LinkedHashMap<>();
    private static final Map<Material, Family> BY_FACE = new ConcurrentHashMap<>();
    private static volatile PlayerPrefs.Entry<Map<String, String>, Map<String, String>> picks;

    private Skins() {}

    public static synchronized @NotNull Family register(@NotNull Family family) {
        if (FAMILIES.putIfAbsent(family.id(), family) != null) throw new IllegalStateException("two families are " + family.id());
        family.faces().forEach(face -> BY_FACE.put(face, family));
        return family;
    }

    /** The family {@code material} belongs to, or null. */
    public static @Nullable Family familyOf(@NotNull Material material) {
        return BY_FACE.get(material);
    }

    /** The face {@code player} wears for {@code family}: their pick, else the plain one. */
    public static @NotNull Material faceOf(@NotNull Player player, @NotNull Family family) {
        Map<String, String> mine = picks != null ? picks.of(player.getUuid()) : null;
        String pick = mine != null ? mine.get(family.id()) : null;
        Material face = pick != null ? Material.fromKey(pick) : null;
        return face != null && family.faces().contains(face) ? face : family.plain();
    }

    public static void pick(@NotNull Player player, @NotNull Family family, @NotNull Material face) {
        if (picks == null) throw new IllegalStateException("Skins.install() first");
        if (!family.faces().contains(face)) throw new IllegalArgumentException(face.key().asString() + " is no face of " + family.id());
        Map<String, String> mine = picks.of(player.getUuid());
        Map<String, String> next = new HashMap<>(mine != null ? mine : Map.of());
        next.put(family.id(), face.key().asString());
        picks.set(player, Map.copyOf(next));
    }

    /** {@code item} in {@code player}'s face for its family; anything of no family as it is. */
    public static @NotNull ItemStack worn(@NotNull Player player, @NotNull ItemStack item) {
        Family family = familyOf(item.material());
        if (family == null) return item;
        Material face = faceOf(player, family);
        return face == item.material() ? item : item.withMaterial(face);
    }

    /** {@code item} in its family's plain face. */
    public static @NotNull ItemStack shed(@NotNull ItemStack item) {
        Family family = familyOf(item.material());
        return family == null || family.plain() == item.material() ? item : item.withMaterial(family.plain());
    }

    /** The kit's skin: the seat's owner wears, and everything leaving is plain. */
    public static @NotNull Kit.Skin skin() {
        return new Kit.Skin() {
            @Override
            public @NotNull ItemStack worn(@NotNull Game game, @NotNull Slot seat, @NotNull ItemStack item) {
                String owner = game.roster().ownerOf(seat);
                Player player = owner != null ? game.memberNamed(owner) : null;
                return player != null ? Skins.worn(player, item) : item;
            }

            @Override
            public @NotNull ItemStack shed(@NotNull Game game, @NotNull ItemStack item) {
                return Skins.shed(item);
            }
        };
    }

    /** Every family registered, in order. */
    public static @NotNull List<Family> families() {
        return List.copyOf(FAMILIES.values());
    }

    /** Registers the preference; at boot, before anyone logs in. The picker is a dev tool for now. */
    public static synchronized void install() {
        if (picks == null) picks = PlayerPrefs.register(KEY);
    }

    /** A face's name as the picker says it. */
    public static @NotNull String name(@NotNull Material face) {
        return face.key().value();
    }
}
