package tennacle.games.items;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * When an item may be used, as a season writes it: {@code allowed}, {@code disabled} (not even bought), or the
 * conditions any one of which unlocks it, joined by {@code or}: {@code beds 1}, {@code beds 2}, {@code beds all},
 * {@code own bed}, {@code event emerald-3}.
 */
public record Unlock(boolean disabled, @NotNull List<Condition> anyOf) {

    public static final Unlock ALLOWED = new Unlock(false, List.of());
    public static final Unlock DISABLED = new Unlock(true, List.of());

    public sealed interface Condition {
        /** {@code count} beds broken anywhere in the game; {@link #ALL} for every one. */
        record Beds(int count) implements Condition {
            public static final int ALL = -1;
        }

        /** The user's own team's bed is gone. */
        record OwnBed() implements Condition {}

        /** A timeline event has fired. */
        record Event(@NotNull String id) implements Condition {}
    }

    /** Usable from the start. */
    public boolean open() {
        return !disabled && anyOf.isEmpty();
    }

    public static @NotNull Unlock parse(@NotNull String text) {
        String spec = text.trim().toLowerCase(Locale.ROOT);
        if (spec.isEmpty() || spec.equals("allowed")) return ALLOWED;
        if (spec.equals("disabled")) return DISABLED;
        List<Condition> conditions = new ArrayList<>();
        for (String part : spec.split("\\s+or\\s+")) conditions.add(condition(part.trim(), text));
        return new Unlock(false, List.copyOf(conditions));
    }

    private static Condition condition(String part, String whole) {
        String[] words = part.split("\\s+");
        if (words.length == 2 && words[0].equals("own") && words[1].equals("bed")) return new Condition.OwnBed();
        if (words.length == 2 && words[0].equals("beds")) {
            if (words[1].equals("all")) return new Condition.Beds(Condition.Beds.ALL);
            try {
                int count = Integer.parseInt(words[1]);
                if (count > 0) return new Condition.Beds(count);
            } catch (NumberFormatException ignored) {
            }
        }
        if (words.length == 2 && words[0].equals("event")) return new Condition.Event(words[1]);
        throw new IllegalArgumentException("not an unlock: \"" + whole + "\" (allowed, disabled, beds <n|all>, own bed,"
                + " event <id>, joined by or)");
    }

    @Override
    public @NotNull String toString() {
        if (disabled) return "disabled";
        if (anyOf.isEmpty()) return "allowed";
        List<String> parts = new ArrayList<>();
        for (Condition c : anyOf) {
            parts.add(switch (c) {
                case Condition.Beds beds -> "beds " + (beds.count() == Condition.Beds.ALL ? "all" : beds.count());
                case Condition.OwnBed ignored -> "own bed";
                case Condition.Event event -> "event " + event.id();
            });
        }
        return String.join(" or ", parts);
    }

    /** As a player reads it: {@code after a bed breaks or Emerald III}. */
    public @Nullable String describe(@NotNull java.util.function.Function<String, String> eventLabel) {
        if (disabled || anyOf.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (Condition c : anyOf) {
            parts.add(switch (c) {
                case Condition.Beds beds -> beds.count() == Condition.Beds.ALL ? "every bed is gone"
                        : beds.count() == 1 ? "a bed breaks" : beds.count() + " beds break";
                case Condition.OwnBed ignored -> "your bed breaks";
                case Condition.Event event -> eventLabel.apply(event.id());
            });
        }
        return "after " + String.join(" or ", parts);
    }
}
