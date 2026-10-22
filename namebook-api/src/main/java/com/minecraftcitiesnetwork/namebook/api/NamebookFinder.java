package com.minecraftcitiesnetwork.namebook.api;

import java.util.Optional;
import org.bukkit.plugin.ServicesManager;

/**
 * Lets a plugin depend on Namebook softly: when Namebook is not installed the result is empty
 * and the caller falls back to its own behaviour.
 */
public final class NamebookFinder {

    private NamebookFinder() {
    }

    /** The registered {@link Namebook} service, or empty when Namebook is not installed. */
    public static Optional<Namebook> find(ServicesManager services) {
        return Optional.ofNullable(services.load(Namebook.class));
    }
}
