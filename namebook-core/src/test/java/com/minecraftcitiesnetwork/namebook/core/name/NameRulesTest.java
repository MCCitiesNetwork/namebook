package com.minecraftcitiesnetwork.namebook.core.name;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NameRulesTest {

    private final NameRules rules = new NameRules(".", true);

    @Test
    void isValid_acceptsJavaAndBedrock() {
        assertTrue(this.rules.isValid("Steve"));
        assertTrue(this.rules.isValid("a_b"));
        assertTrue(this.rules.isValid("_"));
        assertTrue(this.rules.isValid(".BedrockGuy"));
        assertTrue(this.rules.isValid(".Major_Nelson"));
        assertTrue(this.rules.isValid("." + "a".repeat(15)));
    }

    @Test
    void isValid_rejects() {
        assertFalse(this.rules.isValid(""));
        assertFalse(this.rules.isValid("."));
        assertFalse(this.rules.isValid("a b"));
        assertFalse(this.rules.isValid("%"));
        assertFalse(this.rules.isValid("a".repeat(17)));
        assertFalse(this.rules.isValid("." + "a".repeat(16)));
        assertFalse(this.rules.isValid(".Major Nelson"));
        assertFalse(this.rules.isValid(".José"));
        assertFalse(this.rules.isValid(".a-b"));
        assertFalse(this.rules.isValid(null));
    }

    @Test
    void isValid_keepsSpaces_whenNotReplaced() {
        NameRules spaces = new NameRules(".", false);
        assertTrue(spaces.isBedrock(".Major Nelson"));
        assertFalse(spaces.isValid("Major Nelson"));
    }

    @Test
    void customPrefix_star() {
        NameRules star = new NameRules("*", true);
        assertTrue(star.isBedrock("*Guy"));
        assertTrue(star.isValid("*Guy"));
        assertFalse(star.isValid(".Guy"));
        assertEquals("*Major_Nelson", star.bedrockName("Major Nelson"));
        assertEquals("MajorNelson", star.gamertagQuery("*Major_Nelson"));
    }

    @Test
    void longPrefix_limitsTheGamertag() {
        NameRules be = new NameRules("BE-", true);
        assertTrue(be.isBedrock("BE-" + "a".repeat(13)));
        assertFalse(be.isBedrock("BE-" + "a".repeat(14)));
        assertEquals("BE-" + "a".repeat(13), be.bedrockName("a".repeat(15)));
    }

    @Test
    void emptyPrefix_noBedrockByName() {
        NameRules none = new NameRules("", true);
        assertFalse(none.isBedrock("Steve"));
        assertFalse(none.isBedrock(""));
        assertTrue(none.isValid("Steve"));
        assertEquals("Major_Nelson", none.bedrockName("Major Nelson"));
        assertEquals("MajorNelson", none.gamertagQuery("Major_Nelson"));
    }

    @Test
    void bedrockName_replacesSpaces() {
        assertEquals(".Major_Nelson", this.rules.bedrockName("Major Nelson"));
    }

    @Test
    void bedrockName_keepsSpaces_whenNotReplaced() {
        assertEquals(".Major Nelson", new NameRules(".", false).bedrockName("Major Nelson"));
    }

    @Test
    void bedrockName_cutsToSixteen() {
        assertEquals(".ABCDEFGHIJKLMNO", this.rules.bedrockName("ABCDEFGHIJKLMNO"));
        assertEquals(".A_CDEFGHIJKLMNO", this.rules.bedrockName("A CDEFGHIJKLMNOP"));
        assertEquals(16, this.rules.bedrockName("ABCDEFGHIJKLMNO").length());
    }

    @Test
    void gamertagQuery_stripsPrefixUnderscoresAndSpaces() {
        assertEquals("MajorNelson", this.rules.gamertagQuery(".Major_Nelson"));
        assertEquals("MajorNelson", new NameRules(".", false).gamertagQuery(".Major Nelson"));
    }

    @Test
    void mayBeTruncated_onlyAtSixteen() {
        assertTrue(this.rules.mayBeTruncated("." + "a".repeat(15)));
        assertFalse(this.rules.mayBeTruncated("." + "a".repeat(14)));
    }

    @Test
    void prefixIsAmbiguous_forNameCharacters() {
        assertTrue(new NameRules("_", true).prefixIsAmbiguous());
        assertTrue(new NameRules("BE", true).prefixIsAmbiguous());
        assertTrue(new NameRules(".x", true).prefixIsAmbiguous());
        assertFalse(new NameRules(".", true).prefixIsAmbiguous());
        assertFalse(new NameRules("*", true).prefixIsAmbiguous());
        assertFalse(new NameRules("", true).prefixIsAmbiguous());
    }

    @Test
    void floodgate_roundTrip() {
        UUID id = UUID.fromString("00000000-0000-0000-0009-2eeeeeeefd31");
        assertEquals(id, NameRules.floodgateUuid(2584878536129841L));
        assertEquals(2584878536129841L, NameRules.xuid(id));
        assertTrue(NameRules.isFloodgate(id));
        assertFalse(NameRules.isFloodgate(UUID.randomUUID()));
    }

    @Test
    void isFloodgate_rejectsNilUuid() {
        assertFalse(NameRules.isFloodgate(new UUID(0L, 0L)));
    }
}
