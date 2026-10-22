package com.minecraftcitiesnetwork.namebook.core.source;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** One place a name or UUID can be looked up. Implementations never complete exceptionally. */
public interface NameSource {

    SourceId id();

    CompletableFuture<Optional<String>> nameOf(UUID id);

    CompletableFuture<Optional<UUID>> uuidOf(String name);
}
