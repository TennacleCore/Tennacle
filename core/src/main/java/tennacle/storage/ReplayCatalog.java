package tennacle.storage;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Where finished games are listed: who played (as they were named then, on which team), who won, how long it took.
 * The match and replay tables with Postgres; {@link #SAMPLES} makes up a believable history without it.
 */
public interface ReplayCatalog {

    record Seat(@NotNull UUID id, @NotNull String name) {}

    /** {@code team} is what a {@link Record#winner} names; {@code color} a named text color. */
    record Side(@NotNull String team, @NotNull String name, @NotNull String color, @NotNull List<Seat> seats) {}

    /** {@code mode} is the mode's id; {@code winner} null for a draw; {@code watchable} by the player asking. */
    record Record(@NotNull String id, @NotNull String code, @NotNull String mode, @NotNull String map, long playedAt,
                  long lengthMillis, @NotNull List<Side> sides, @Nullable String winner, boolean sample, boolean watchable,
                  boolean bookmarked) {}

    /** {@code player}'s latest games, newest first. */
    @NotNull CompletableFuture<List<Record>> of(@NotNull UUID player, @NotNull String name, int limit);

    /** Thirty made-up games a player seems to have played, the same ones every time for the same player. */
    ReplayCatalog SAMPLES = new ReplayCatalog() {
        private static final String[][] GAMES = {
                {"bridge", "Urban", "1"}, {"bedwars-doubles", "Lighthouse", "2"}, {"sumo", "Ring", "1"},
                {"boxing", "Arena", "1"}, {"bridge", "Sorcery", "2"}, {"bedfight", "Mesa", "1"},
                {"bedwars-solo", "Airshow", "1"}};
        private static final String[] OTHERS = {"Kai", "Noor", "Rin", "Ezra", "Mika", "Jun", "Sami", "Theo", "Ivy", "Lior"};
        private static final String[][] TEAMS = {{"red", "blue"}, {"red", "blue", "green", "yellow", "aqua", "white", "pink", "gray"}};

        @Override
        public @NotNull CompletableFuture<List<Record>> of(@NotNull UUID player, @NotNull String name, int limit) {
            Random random = new Random(player.getMostSignificantBits() ^ player.getLeastSignificantBits());
            List<Record> out = new ArrayList<>();
            long at = System.currentTimeMillis() - 20 * 60_000L;
            for (int i = 0; i < Math.min(30, limit); i++) {
                String[] game = GAMES[random.nextInt(GAMES.length)];
                int size = Integer.parseInt(game[2]);
                String[] teams = game[0].startsWith("bedwars") ? TEAMS[1] : TEAMS[0];
                List<Side> sides = new ArrayList<>();
                for (int t = 0; t < teams.length; t++) {
                    List<Seat> seats = new ArrayList<>();
                    for (int s = 0; s < size; s++) {
                        boolean me = t == 0 && s == 0;
                        String who = me ? name : OTHERS[random.nextInt(OTHERS.length)];
                        seats.add(new Seat(me ? player : UUID.nameUUIDFromBytes(who.getBytes()), who));
                    }
                    String color = teams[t].equals("pink") ? "light_purple" : teams[t];
                    sides.add(new Side(teams[t], Character.toUpperCase(teams[t].charAt(0)) + teams[t].substring(1), color, seats));
                }
                String winner = teams[random.nextInt(Math.min(teams.length, 3)) == 0 ? 0 : 1 + random.nextInt(teams.length - 1)];
                long length = (game[0].equals("sumo") ? 30 : game[0].startsWith("bedwars") ? 600 : 240) * 1000L
                        + random.nextInt(300_000);
                String code = Long.toString(Math.abs(random.nextLong()) % 2_176_782_336L, 36).toUpperCase();
                out.add(new Record("sample-" + i, code, game[0], game[1], at, length, sides, winner, true, false, false));
                at -= length + 60_000L + random.nextInt(3 * 3_600_000);
            }
            return CompletableFuture.completedFuture(out);
        }
    };
}
