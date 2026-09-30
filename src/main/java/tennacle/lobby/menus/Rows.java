package tennacle.lobby.menus;

/** Where a row of items sits: centered, a slot apart while they fit, side by side past five. */
final class Rows {

    private Rows() {}

    static int[] centered(int row, int count) {
        int[] slots = new int[count];
        boolean apart = count <= 5;
        int start = apart ? 4 - (count - 1) : 4 - (count - 1) / 2;
        for (int i = 0; i < count; i++) slots[i] = row * 9 + start + (apart ? 2 * i : i);
        return slots;
    }
}
