package com.minecraftcitiesnetwork.namebook.core.name;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Decides which names Namebook accepts and converts between Bedrock names, gamertags and Floodgate UUIDs.
 * Bedrock names follow Floodgate's own rule, so a name built here is the name the player has in game.
 */
public final class NameRules {

    /** Minecraft's limit; Floodgate cuts Bedrock names to it too. */
    public static final int MAX_NAME_LENGTH = 16;

    private static final Pattern JAVA = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern NAME_CHARACTER = Pattern.compile("[A-Za-z0-9_]");

    private final String bedrockPrefix;
    private final boolean replaceSpaces;
    /** Null when the prefix is empty or leaves no room, because then no name can be told apart as Bedrock. */
    private final @Nullable Pattern bedrock;

    public NameRules(String bedrockPrefix, boolean replaceSpaces) {
        this.bedrockPrefix = bedrockPrefix;
        this.replaceSpaces = replaceSpaces;
        int room = MAX_NAME_LENGTH - bedrockPrefix.length();
        // Gamertags are ASCII letters, digits and spaces; spaces stay only when Floodgate keeps them.
        String characters = replaceSpaces ? "[A-Za-z0-9_]" : "[A-Za-z0-9_ ]";
        this.bedrock = bedrockPrefix.isEmpty() || room < 1
                ? null
                : Pattern.compile("^" + Pattern.quote(bedrockPrefix) + characters + "{1," + room + "}$");
    }

    public boolean isValid(@Nullable String name) {
        if (name == null) {
            return false;
        }
        return JAVA.matcher(name).matches() || this.isBedrock(name);
    }

    public boolean isBedrock(@Nullable String name) {
        return name != null && this.bedrock != null && this.bedrock.matcher(name).matches();
    }

    /**
     * Builds the name exactly as Floodgate does: the gamertag is cut so the whole name fits in 16
     * characters, and only then are spaces replaced.
     */
    public String bedrockName(String gamertag) {
        int room = Math.max(0, MAX_NAME_LENGTH - this.bedrockPrefix.length());
        String name = this.bedrockPrefix + gamertag.substring(0, Math.min(gamertag.length(), room));
        return this.replaceSpaces ? name.replace(' ', '_') : name;
    }

    /**
     * Underscores and spaces are removed because a Bedrock name cannot tell a replaced space from
     * a real underscore, and playerdb.co ignores spaces in gamertags.
     */
    public String gamertagQuery(String bedrockName) {
        String withoutPrefix = bedrockName.startsWith(this.bedrockPrefix)
                ? bedrockName.substring(this.bedrockPrefix.length())
                : bedrockName;
        return withoutPrefix.replace("_", "").replace(" ", "");
    }

    /**
     * A Bedrock name at the 16-character limit may be a cut-down gamertag, so a lookup by its
     * gamertag could find a different, shorter account.
     */
    public boolean mayBeTruncated(String bedrockName) {
        return bedrockName.length() == MAX_NAME_LENGTH;
    }

    /** A prefix with name characters lets a valid Java name look like a Bedrock name. */
    public boolean prefixIsAmbiguous() {
        return !this.bedrockPrefix.isEmpty() && NAME_CHARACTER.matcher(this.bedrockPrefix).find();
    }

    public String bedrockPrefix() {
        return this.bedrockPrefix;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The nil UUID has zero high bits too, but no Xbox account has XUID 0. */
    public static boolean isFloodgate(UUID id) {
        return id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() != 0L;
    }

    public static long xuid(UUID floodgateId) {
        return floodgateId.getLeastSignificantBits();
    }

    public static UUID floodgateUuid(long xuid) {
        return new UUID(0L, xuid);
    }
}
