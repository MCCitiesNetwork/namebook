package com.minecraftcitiesnetwork.namebook.api;

import java.util.Optional;
import org.bukkit.plugin.ServicesManager;

/**
 * Lets a plugin depend on Namebook softly: when Namebook's service is not registered the result
 * is empty and the caller falls back to its own behaviour.
 *
 * <p>This class is itself part of Namebook. When the Namebook plugin is not installed it does not
 * exist, and calling it throws {@link NoClassDefFoundError}. Before touching this class or
 * {@link Namebook}:
 * <ul>
 *   <li>check {@code getServer().getPluginManager().getPlugin("Namebook") != null};</li>
 *   <li>keep every use of Namebook's types in a separate class that is loaded only after that check;</li>
 *   <li>catch {@link LinkageError} around that first use, in case the installed version does not match.</li>
 * </ul>
 * Declare Namebook in {@code paper-plugin.yml} under {@code dependencies.server} with
 * {@code load: BEFORE}, {@code required: false} and {@code join-classpath: true}.
 */
public final class NamebookFinder {

    private NamebookFinder() {
    }

    /** The registered {@link Namebook} service, or empty when Namebook is not installed. */
    public static Optional<Namebook> find(ServicesManager services) {
        return Optional.ofNullable(services.load(Namebook.class));
    }
}
