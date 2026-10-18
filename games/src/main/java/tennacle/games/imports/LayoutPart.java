package tennacle.games.imports;

import com.google.gson.JsonElement;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * One shareable part of a mode's layout (Bed Wars quick buy) in our own JSON shape: {@code export} reads anyone's,
 * online or not (null for none of their own), {@code apply} makes it a player's. Every source lands through
 * {@code apply}.
 */
public record LayoutPart(@NotNull String mode, @NotNull String modeTitle, @NotNull String part, @NotNull String partTitle,
                         @NotNull Material icon, @NotNull Function<UUID, CompletableFuture<@Nullable JsonElement>> export,
                         @NotNull BiConsumer<Player, JsonElement> apply) {

    @NotNull String label() {
        return modeTitle + " " + partTitle.toLowerCase(Locale.ROOT);
    }
}
