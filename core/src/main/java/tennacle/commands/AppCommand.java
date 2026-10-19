package tennacle.commands;

import tennacle.access.Access;
import tennacle.access.Role;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.CommandExecutor;
import net.minestom.server.command.builder.CommandSyntax;
import net.minestom.server.command.builder.arguments.Argument;
import net.minestom.server.command.builder.condition.CommandCondition;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A command the app registers: the Minestom command plus what {@code /help} and the welcome say about it. Every
 * executor runs guarded, so a throwing command tells the player instead of going silent.
 */
public final class AppCommand extends Command {

    /** A player surface ({@code tennacle.command.<name>}), or a staff tool ({@code tennacle.staff.<name>}). */
    public enum Audience { PLAYER, STAFF }

    /** One {@code /help} row: the form as typed, and what it does; {@code node}, when set, is what reading it takes. */
    public record Usage(@NotNull String form, @NotNull String what, @Nullable String node) {}

    private final Audience audience;
    private final String blurb;
    private final List<Usage> usages = new ArrayList<>();
    private String node;
    private @Nullable Role lowest;
    private boolean featured;

    AppCommand(@NotNull Audience audience, @NotNull String blurb, @NotNull String name, @NotNull String... aliases) {
        super(name, aliases);
        this.audience = audience;
        this.blurb = blurb;
        this.node = (audience == Audience.STAFF ? "tennacle.staff." : "tennacle.command.") + name;
    }

    public @NotNull AppCommand usage(@NotNull String form, @NotNull String what) {
        return usage(form, what, null);
    }

    /** A form only some may use: listed for those who hold {@code node}. */
    public @NotNull AppCommand usage(@NotNull String form, @NotNull String what, @Nullable String node) {
        usages.add(new Usage(form, what, node));
        return this;
    }

    /** What seeing and running it takes; set before it registers. */
    public @NotNull AppCommand node(@NotNull String node) {
        this.node = node;
        return this;
    }

    public @NotNull String node() {
        return node;
    }

    /** The lowest staff role that holds it ({@link Access#lowest}); unset, a staff tool is the admin's alone. */
    public @NotNull AppCommand role(@NotNull Role role) {
        this.lowest = role;
        return this;
    }

    public @Nullable Role role() {
        return lowest;
    }

    /** Listed on the welcome screen. */
    public @NotNull AppCommand featured() {
        featured = true;
        return this;
    }

    public @NotNull Audience audience() {
        return audience;
    }

    public @NotNull String blurb() {
        return blurb;
    }

    public boolean isFeatured() {
        return featured;
    }

    /** The declared rows, or the bare command with its blurb when none were. */
    public @NotNull List<Usage> usages() {
        return usages.isEmpty() ? List.of(new Usage("/" + getName(), blurb, null)) : List.copyOf(usages);
    }

    public void usage(@NotNull CommandSender to) {
        Commands.usage(to, this);
    }

    // addSyntax, in both forms, lands here
    @Override
    public @NotNull Collection<CommandSyntax> addConditionalSyntax(@Nullable CommandCondition condition,
                                                                   @NotNull CommandExecutor executor,
                                                                   @NotNull Argument<?>... args) {
        return super.addConditionalSyntax(condition, Commands.guard(this, executor), args);
    }

    @Override
    public void setDefaultExecutor(@Nullable CommandExecutor executor) {
        super.setDefaultExecutor(executor == null ? null : Commands.guard(this, executor));
    }
}
