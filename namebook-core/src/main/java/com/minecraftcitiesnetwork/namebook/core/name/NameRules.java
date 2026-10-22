package com.minecraftcitiesnetwork.namebook.core.name;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/** Decides which names Namebook accepts and converts between Bedrock names, gamertags and Floodgate UUIDs. */
public final class NameRules {

    private static final Pattern JAVA = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private final String bedrockPrefix;
    /** Null when the prefix is empty, because then no name can be told apart as Bedrock. */
    private final @Nullable Pattern bedrock;

    public NameRules(String bedrockPrefix) {
        this.bedrockPrefix = bedrockPrefix;
        this.bedrock = bedrockPrefix.isEmpty()
                ? null
                : Pattern.compile("^" + Pattern.quote(bedrockPrefix) + "[^\\s]{1,31}$");
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

    /** Floodgate writes the gamertag with spaces replaced by underscores, after the prefix. */
    public String bedrockName(String gamertag) {
        return this.bedrockPrefix + gamertag.replace(' ', '_');
    }

    /** Underscores are removed because a gamertag has no way to tell a space from a real underscore. */
    public String gamertagQuery(String bedrockName) {
        String withoutPrefix = bedrockName.startsWith(this.bedrockPrefix)
                ? bedrockName.substring(this.bedrockPrefix.length())
                : bedrockName;
        return withoutPrefix.replace("_", "");
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean isFloodgate(UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    public static long xuid(UUID floodgateId) {
        return floodgateId.getLeastSignificantBits();
    }

    public static UUID floodgateUuid(long xuid) {
        return new UUID(0L, xuid);
    }
}
