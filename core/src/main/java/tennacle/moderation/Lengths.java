package tennacle.moderation;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Lengths {

    private static final Pattern LENGTH = Pattern.compile("(\\d{1,6})([smhdw])");
    private static final long[] UNITS = {604_800_000L, 86_400_000L, 3_600_000L, 60_000L, 1_000L};
    private static final String[] UNIT_NAMES = {"week", "day", "hour", "minute", "second"};

    private Lengths() {}

    /** {@code 45s}, {@code 30m}, {@code 2h}, {@code 7d} or {@code 1w} in millis, {@link Punishments#FOREVER} for {@code perm}; -1 when it isn't one. */
    static long parse(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        if (lower.equals("perm") || lower.equals("permanent")) return Punishments.FOREVER;
        Matcher m = LENGTH.matcher(lower);
        if (!m.matches()) return -1;
        long n = Long.parseLong(m.group(1));
        if (n == 0) return -1;
        return n * UNITS["wdhms".indexOf(m.group(2))];
    }

    // rounded up in the largest unit it fills: 89 minutes reads 2 hours
    static String span(long millis) {
        long left = Math.max(millis, 1);
        for (int i = 0; i < UNITS.length; i++) {
            if (left < UNITS[i] && i < UNITS.length - 1) continue;
            long n = (left + UNITS[i] - 1) / UNITS[i];
            return n + " " + UNIT_NAMES[i] + (n == 1 ? "" : "s");
        }
        throw new AssertionError();
    }

    /** "permanently", or "for 2 hours" from {@code from}. */
    static String lasting(Punishments.Punishment p, long from) {
        return p.permanent() ? "permanently" : "for " + span(p.until() - from);
    }

    static String why(Punishments.Punishment p) {
        return p.reason().isEmpty() ? "" : " Reason: " + p.reason();
    }
}
