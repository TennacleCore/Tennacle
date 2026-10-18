package tennacle.games.imports;

import com.google.gson.JsonObject;
import net.minestom.server.entity.Player;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One part of a mode's layout from one source (Bed Wars quick buy from Hypixel). {@code read} turns the source's
 * profile into the change to make, or null when the player has none saved there.
 */
public record LayoutImport(@NotNull String source, @NotNull String mode, @NotNull String modeTitle,
                           @NotNull String part, @NotNull String partTitle, @NotNull Material icon,
                           @NotNull Function<JsonObject, @Nullable Consumer<Player>> read) {

    @NotNull String label() {
        return modeTitle + " " + partTitle.toLowerCase(java.util.Locale.ROOT);
    }
}
