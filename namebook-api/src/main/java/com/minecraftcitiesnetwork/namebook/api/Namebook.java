package com.minecraftcitiesnetwork.namebook.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Resolves player names to UUIDs and back.
 *
 * <p>Guarantees for every method:
 * <ul>
 *   <li>Safe to call from any thread. A failure in any source counts as "unknown" for that
 *       source, so the returned future never completes exceptionally.</li>
 *   <li>Futures complete on Namebook's own executor, never the main thread. Callers that touch
 *       Bukkit must hop back themselves.</li>
 *   <li>Bulk calls deduplicate their input and make at most one database round trip for the
 *       identifiers the cache does not hold.</li>
 * </ul>
 */
public interface Namebook {

    /** The player's current name, or empty when no source knows the UUID. */
    CompletableFuture<Optional<String>> nameOf(UUID player);

    /** The UUID of the player who currently has this name (case-insensitive), or empty. */
    CompletableFuture<Optional<UUID>> uuidOf(String name);

    /** Every requested UUID is a key, in request order. */
    CompletableFuture<Map<UUID, Optional<String>>> namesOf(Collection<UUID> players);

    /** Every requested name is a key, in request order, with the caller's spelling. */
    CompletableFuture<Map<String, Optional<UUID>>> uuidsOf(Collection<String> names);
}
