package com.minecraftcitiesnetwork.namebook.paper.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Setting;

import java.time.Duration;

/**
 * Plugin behaviour settings from {@code settings.yml}. Every compact constructor repairs
 * missing or nonsensical values, so a hand-edited file can never leave a null or a zero
 * timeout in the running plugin.
 */
@ConfigSerializable
public record Settings(Bedrock bedrock, Cache cache, Remote playerdb, Remote mojang) {

    public Settings {
        bedrock = bedrock == null ? new Bedrock(null, true) : bedrock;
        cache = cache == null ? new Cache(null, null, 0) : cache;
        playerdb = playerdb == null ? new Remote(true, null, 0) : playerdb;
        mojang = mojang == null ? new Remote(true, null, 0) : mojang;
    }

    /**
     * How Floodgate builds Bedrock names, so Namebook can tell them from Java names. An empty
     * prefix is allowed. Floodgate's own config.yml overrides both values when it exists.
     */
    @ConfigSerializable
    public record Bedrock(String prefix, @Setting("replace-spaces") boolean replaceSpaces) {
        public Bedrock {
            // Only a missing key falls back; an explicit empty prefix is a valid choice.
            prefix = prefix == null ? "." : prefix;
        }
    }

    /** The in-memory cache in front of the database and remote sources. */
    @ConfigSerializable
    public record Cache(
            Duration ttl,
            @Setting("miss-ttl") Duration missTtl,
            @Setting("max-entries") int maxEntries
    ) {
        public Cache {
            ttl = positive(ttl, Duration.ofMinutes(10));
            missTtl = positive(missTtl, Duration.ofMinutes(1));
            if (maxEntries <= 0) {
                maxEntries = 10000;
            }
        }
    }

    /** One remote name source. */
    @ConfigSerializable
    public record Remote(
            boolean enabled,
            Duration timeout,
            @Setting("max-per-minute") int maxPerMinute
    ) {
        public Remote {
            timeout = positive(timeout, Duration.ofSeconds(3));
            if (maxPerMinute <= 0) {
                maxPerMinute = 30;
            }
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
