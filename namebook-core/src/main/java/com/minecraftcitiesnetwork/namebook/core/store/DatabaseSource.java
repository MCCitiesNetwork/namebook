package com.minecraftcitiesnetwork.namebook.core.store;

import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Runs {@link PlayerStore} reads on the database executor. A failed read, such as the database
 * being down, is logged and answered as unknown so the lookup chain can move on.
 */
public final class DatabaseSource implements NameSource {

    private final PlayerStore store;
    private final Executor executor;
    private final Logger log;

    public DatabaseSource(@NotNull PlayerStore store, @NotNull Executor executor, @NotNull Logger log) {
        this.store = store;
        this.executor = executor;
        this.log = log;
    }

    @Override
    public SourceId id() {
        return SourceId.DATABASE;
    }

    @Override
    public CompletableFuture<Optional<String>> nameOf(UUID id) {
        return this.read(() -> this.store.nameOf(id), Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<UUID>> uuidOf(String name) {
        return this.read(() -> this.store.uuidOf(name), Optional.empty());
    }

    /** One query for every id; ids with no row are absent. */
    public CompletableFuture<Map<UUID, String>> namesOf(Collection<UUID> ids) {
        return this.read(() -> this.store.namesOf(ids), Map.of());
    }

    /** One query for every name; keys are lower-case, and unknown names are absent. */
    public CompletableFuture<Map<String, UUID>> uuidsOf(Collection<String> names) {
        return this.read(() -> this.store.uuidsOf(names), Map.of());
    }

    private <T> CompletableFuture<T> read(Supplier<T> query, T unknown) {
        try {
            return CompletableFuture.supplyAsync(query, this.executor).exceptionally(ex -> {
                this.log.log(Level.FINE, "Database lookup failed; treating it as unknown", ex);
                return unknown;
            });
        } catch (RuntimeException ex) {
            // The executor refuses work while the plugin shuts down.
            this.log.log(Level.FINE, "Database lookup could not be scheduled; treating it as unknown", ex);
            return CompletableFuture.completedFuture(unknown);
        }
    }
}
