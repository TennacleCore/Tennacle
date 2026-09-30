package tennacle.looks;

import io.github.term4.canvas.theme.Livery;
import io.github.term4.grotto.roster.Team;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaletteTest {

    /** Each BedWars team as its arena authors it, printed as the board, chat tag and banners read it. */
    @Test
    void bedwarsTeamsPrintAsCaptured() {
        List<String> displays = List.of("red", "blue", "green", "yellow", "aqua", "white", "light_purple", "dark_gray");
        List<String> printed = displays.stream().map(display -> {
            Team team = new Team("1", new Livery(Map.of("display", display)));
            return Palette.letter(team) + " " + Palette.titled(team) + " [" + Palette.name(team) + "]";
        }).toList();
        assertEquals(List.of("R Red [RED]", "B Blue [BLUE]", "G Green [GREEN]", "Y Yellow [YELLOW]", "A Aqua [AQUA]",
                "W White [WHITE]", "P Pink [PINK]", "S Gray [GRAY]"), printed);
    }
}
