package com.minecraftcitiesnetwork.namebook.paper.listener;

import com.minecraftcitiesnetwork.namebook.core.chain.LookupChain;
import com.minecraftcitiesnetwork.namebook.core.store.PlayerStore;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records every player's UUID and current name when they join. The cache is updated on the main
 * thread straight away, so lookups on this server are right at once; the database write follows
 * on the database executor. A failed write never affects the join.
 */
public final class JoinRecorder implements Listener {

    /** A database outage would otherwise log once for every join. */
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private final PlayerStore store;
    private final LookupChain chain;
    private final Executor database;
    private final Clock clock;
    private final Logger log;
    private final Map<UUID, Instant> lastWarning = new ConcurrentHashMap<>();

    public JoinRecorder(@NotNull PlayerStore store, @NotNull LookupChain chain, @NotNull Executor database,
                        @NotNull Clock clock, @NotNull Logger log) {
        this.store = store;
        this.chain = chain;
        this.database = database;
        this.clock = clock;
        this.log = log;
    }

    // MONITOR: Namebook only observes the join, and should see the name the player finally joined with.
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        this.record(event.getPlayer());
    }

    /** Records players already online, for when the plugin enables on a running server. */
    public void recordOnline(@NotNull Collection<? extends Player> players) {
        for (Player player : players) {
            this.record(player);
        }
    }

    private void record(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        this.chain.recordLocally(id, name);
        try {
            this.database.execute(() -> {
                try {
                    this.store.record(id, name);
                } catch (RuntimeException ex) {
                    this.warn(id, name, ex);
                }
            });
        } catch (RuntimeException ex) {
            // The executor refuses work while the plugin shuts down.
            this.warn(id, name, ex);
        }
    }

    private void warn(UUID id, String name, Exception ex) {
        Instant now = this.clock.instant();
        // Forget players whose minute is up, so the map holds only recent failures.
        this.lastWarning.values().removeIf(at -> !now.isBefore(at.plus(WARNING_INTERVAL)));
        AtomicBoolean due = new AtomicBoolean();
        this.lastWarning.compute(id, (key, previous) -> {
            if (previous != null && now.isBefore(previous.plus(WARNING_INTERVAL))) {
                return previous;
            }
            due.set(true);
            return now;
        });
        if (due.get()) {
            this.log.warning("Could not save " + name + " (" + id + ") to the database: " + ex.getMessage()
                    + ". Further failures for this player are not logged for a minute.");
            this.log.log(Level.FINE, "Join record failure detail", ex);
        }
    }
}
