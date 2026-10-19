package tennacle.net;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNull;

/** A named channel carrying one record type, sent as JSON. */
public record Topic<T>(@NotNull String name, @NotNull Class<T> type) {

    private static final Gson GSON = new Gson();

    public @NotNull String encode(@NotNull T event) {
        return GSON.toJson(event);
    }

    public @NotNull T decode(@NotNull String json) {
        return GSON.fromJson(json, type);
    }
}
