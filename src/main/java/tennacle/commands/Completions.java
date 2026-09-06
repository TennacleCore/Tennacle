package tennacle.commands;

import net.minestom.server.command.builder.suggestion.Suggestion;
import net.minestom.server.command.builder.suggestion.SuggestionCallback;
import net.minestom.server.command.builder.suggestion.SuggestionEntry;

import java.util.Collection;
import java.util.function.Supplier;

/** The word under the cursor, and the entries that start with it. */
public final class Completions {

    private Completions() {}

    /** Past the suggestion's start (which counts the leading '/'), minus the placeholder the listener appends
     *  when the next word is still empty. */
    public static String partial(Suggestion suggestion) {
        return suggestion.getInput().substring(Math.max(0, suggestion.getStart() - 1)).replace("\0", "");
    }

    public static void offer(Suggestion suggestion, Collection<String> entries) {
        String partial = partial(suggestion);
        for (String entry : entries) {
            if (entry.regionMatches(true, 0, partial, 0, partial.length())) suggestion.addEntry(new SuggestionEntry(entry));
        }
    }

    /** Entries read at each request, so a registry that fills after the command exists still completes. */
    public static SuggestionCallback of(Supplier<? extends Collection<String>> entries) {
        return (sender, context, suggestion) -> offer(suggestion, entries.get());
    }
}
