package com.minecraftcitiesnetwork.namebook.core.name;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NameRulesTest {

    private final NameRules rules = new NameRules(".");

    @Test
    void isValid_acceptsJavaAndBedrock() {
        assertTrue(this.rules.isValid("Steve"));
        assertTrue(this.rules.isValid("a_b"));
        assertTrue(this.rules.isValid("_"));
        assertTrue(this.rules.isValid(".BedrockGuy"));
    }

    @Test
    void isValid_rejects() {
        assertFalse(this.rules.isValid(""));
        assertFalse(this.rules.isValid("."));
        assertFalse(this.rules.isValid("a b"));
        assertFalse(this.rules.isValid("%"));
        assertFalse(this.rules.isValid("a".repeat(17)));
        assertFalse(this.rules.isValid("." + "a".repeat(32)));
        assertFalse(this.rules.isValid(null));
    }

    @Test
    void customPrefix_star() {
        NameRules star = new NameRules("*");
        assertTrue(star.isBedrock("*Guy"));
        assertTrue(star.isValid("*Guy"));
        assertFalse(star.isValid(".Guy"));
    }

    @Test
    void emptyPrefix_noBedrockByName() {
        NameRules none = new NameRules("");
        assertFalse(none.isBedrock("Steve"));
        assertFalse(none.isBedrock(""));
        assertTrue(none.isValid("Steve"));
    }

    @Test
    void bedrockName_replacesSpaces() {
        assertEquals(".Major_Nelson", this.rules.bedrockName("Major Nelson"));
    }

    @Test
    void gamertagQuery_stripsPrefixAndUnderscores() {
        assertEquals("MajorNelson", this.rules.gamertagQuery(".Major_Nelson"));
    }

    @Test
    void floodgate_roundTrip() {
        UUID id = UUID.fromString("00000000-0000-0000-0009-2eeeeeeefd31");
        assertEquals(id, NameRules.floodgateUuid(2584878536129841L));
        assertEquals(2584878536129841L, NameRules.xuid(id));
        assertTrue(NameRules.isFloodgate(id));
        assertFalse(NameRules.isFloodgate(UUID.randomUUID()));
    }
}
