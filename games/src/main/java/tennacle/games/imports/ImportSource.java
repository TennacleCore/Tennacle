package tennacle.games.imports;

import com.google.gson.JsonObject;
import net.minestom.server.item.Material;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Somewhere a player's layouts live (Hypixel): {@code fetch} reads their profile, failing with a reason they can read. */
public record ImportSource(@NotNull String id, @NotNull String title, @NotNull Material icon, @NotNull BooleanSupplier ready,
                           @NotNull Function<UUID, CompletableFuture<JsonObject>> fetch) {}
