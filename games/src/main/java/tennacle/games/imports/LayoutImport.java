package tennacle.games.imports;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/** A {@link LayoutPart} as one source keeps it: {@code read} turns the source's profile into the part's shape, or null for none. */
public record LayoutImport(@NotNull String source, @NotNull String mode, @NotNull String part,
                           @NotNull Function<JsonObject, @Nullable JsonElement> read) {}
