package tennacle.commands;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandNameTest {

    @Test
    void onlyTheNameLowers() {
        assertEquals("ac Hello There", Commands.lowerName("AC Hello There"));
        assertEquals("msg Term4 hi", Commands.lowerName("MsG Term4 hi"));
        assertEquals("l", Commands.lowerName("L"));
        assertEquals("party invite Dogln", Commands.lowerName("party invite Dogln"));
    }
}
