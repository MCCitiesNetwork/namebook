package com.minecraftcitiesnetwork.namebook.core.chain;

import com.minecraftcitiesnetwork.namebook.api.Namebook;
import com.minecraftcitiesnetwork.namebook.core.cache.NameCache;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.source.Resolution;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import com.minecraftcitiesnetwork.namebook.core.store.DatabaseSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Answers lookups from the cache, then the database, then each fallback source in order, stopping
 * at the first answer. Answers and misses are cached. Every returned future completes on the
 * executor given here and never completes exceptionally.
 */
public final class LookupChain implements Namebook {

    private final NameRules rules;
    private final NameCache cache;
    private final DatabaseSource database;
    private final List<NameSource> fallbacks;
    /** The database followed by the fallbacks: the order single lookups ask in after the cache. */
    private final List<NameSource> sources;
    private final Executor executor;
    private final Logger log;

    public LookupChain(@NotNull NameRules rules, @NotNull NameCache cache, @NotNull DatabaseSource database,
                       @NotNull List<NameSource> fallbacks, @NotNull Executor executor, @NotNull Logger log) {
        this.rules = rules;
        this.cache = cache;
        this.database = database;
        this.fallbacks = List.copyOf(fallbacks);
        List<NameSource> all = new ArrayList<>();
        all.add(database);
        all.addAll(fallbacks);
        this.sources = List.copyOf(all);
        this.executor = executor;
        this.log = log;
    }

    /** Caches a name this server has just seen, so lookups here are right before the database write lands. */
    public void recordLocally(@NotNull UUID id, @NotNull String name) {
        this.cache.putJoined(id, name);
    }

    @Override
    public CompletableFuture<Optional<String>> nameOf(@Nullable UUID player) {
        return this.finish(() -> this.findName(player).thenApply(Resolution::value), Optional.empty());
    }

    @Override
    public CompletableFuture<Optional<UUID>> uuidOf(@Nullable String name) {
        return this.finish(() -> this.findUuid(name).thenApply(Resolution::value), Optional.empty());
    }

    /** Like {@link #nameOf} but also reports which source answered, for the lookup command. */
    public CompletableFuture<Resolution<String>> resolveName(@Nullable UUID id) {
        return this.finish(() -> this.findName(id), Resolution.unknown());
    }

    /** Like {@link #uuidOf} but also reports which source answered, for the lookup command. */
    public CompletableFuture<Resolution<UUID>> resolveUuid(@Nullable String name) {
        return this.finish(() -> this.findUuid(name), Resolution.unknown());
    }

    @Override
    public CompletableFuture<Map<UUID, Optional<String>>> namesOf(@Nullable Collection<UUID> players) {
        List<UUID> request = players == null ? List.of() : new ArrayList<>(players);
        return this.finish(() -> this.findNames(request), unknownFor(request));
    }

    @Override
    public CompletableFuture<Map<String, Optional<UUID>>> uuidsOf(@Nullable Collection<String> names) {
        List<String> request = names == null ? List.of() : new ArrayList<>(names);
        return this.finish(() -> this.findUuids(request), unknownFor(request));
    }

    private CompletableFuture<Resolution<String>> findName(@Nullable UUID id) {
        if (id == null) {
            return CompletableFuture.completedFuture(Resolution.unknown());
        }
        long since = this.cache.stamp();
        Optional<Optional<String>> cached = this.cache.nameOf(id);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(fromCache(cached.get()));
        }
        return this.ask(this.sources, 0, source -> source.nameOf(id)).thenApply(resolution -> {
            this.rememberName(id, resolution.value(), since);
            return resolution;
        });
    }

    private CompletableFuture<Resolution<UUID>> findUuid(@Nullable String name) {
        if (!this.rules.isValid(name)) {
            return CompletableFuture.completedFuture(Resolution.unknown());
        }
        long since = this.cache.stamp();
        Optional<Optional<UUID>> cached = this.cache.uuidOf(name);
        if (cached.isPresent()) {
            return CompletableFuture.completedFuture(fromCache(cached.get()));
        }
        return this.ask(this.sources, 0, source -> source.uuidOf(name)).thenApply(resolution -> {
            this.rememberUuid(name, resolution.value(), since);
            return resolution;
        });
    }

    private CompletableFuture<Map<UUID, Optional<String>>> findNames(List<UUID> request) {
        long since = this.cache.stamp();
        Map<UUID, Optional<String>> answers = new ConcurrentHashMap<>();
        List<UUID> pending = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(request)) {
            if (id == null) {
                continue;
            }
            Optional<Optional<String>> cached = this.cache.nameOf(id);
            if (cached.isPresent()) {
                answers.put(id, cached.get());
            } else {
                pending.add(id);
            }
        }
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(inOrder(request, answers, Function.identity()));
        }
        return this.guard(this.database.id(), () -> this.database.namesOf(pending), Map.<UUID, String>of())
                .thenCompose(stored -> {
                    List<CompletableFuture<?>> remote = new ArrayList<>();
                    for (UUID id : pending) {
                        String name = stored.get(id);
                        if (name != null) {
                            this.cache.put(id, name, since);
                            answers.put(id, Optional.of(name));
                            continue;
                        }
                        remote.add(this.ask(this.fallbacks, 0, source -> source.nameOf(id)).thenAccept(resolution -> {
                            this.rememberName(id, resolution.value(), since);
                            answers.put(id, resolution.value());
                        }));
                    }
                    return CompletableFuture.allOf(remote.toArray(CompletableFuture[]::new));
                })
                .thenApply(done -> inOrder(request, answers, Function.identity()));
    }

    private CompletableFuture<Map<String, Optional<UUID>>> findUuids(List<String> request) {
        // Keyed by NameRules.key so "Steve" and "STEVE" are looked up once; the first spelling is the one asked.
        long since = this.cache.stamp();
        Map<String, Optional<UUID>> answers = new ConcurrentHashMap<>();
        Map<String, String> pending = new LinkedHashMap<>();
        for (String name : request) {
            if (!this.rules.isValid(name)) {
                continue;
            }
            String key = NameRules.key(name);
            if (answers.containsKey(key) || pending.containsKey(key)) {
                continue;
            }
            Optional<Optional<UUID>> cached = this.cache.uuidOf(name);
            if (cached.isPresent()) {
                answers.put(key, cached.get());
            } else {
                pending.put(key, name);
            }
        }
        Function<String, @Nullable String> keyOfValid = name -> this.rules.isValid(name) ? NameRules.key(name) : null;
        if (pending.isEmpty()) {
            return CompletableFuture.completedFuture(inOrder(request, answers, keyOfValid));
        }
        List<String> spellings = List.copyOf(pending.values());
        return this.guard(this.database.id(), () -> this.database.uuidsOf(spellings), Map.<String, UUID>of())
                .thenCompose(stored -> {
                    List<CompletableFuture<?>> remote = new ArrayList<>();
                    for (Map.Entry<String, String> entry : pending.entrySet()) {
                        String key = entry.getKey();
                        String name = entry.getValue();
                        UUID id = stored.get(key);
                        if (id != null) {
                            this.rememberUuid(name, Optional.of(id), since);
                            answers.put(key, Optional.of(id));
                            continue;
                        }
                        remote.add(this.ask(this.fallbacks, 0, source -> source.uuidOf(name)).thenAccept(resolution -> {
                            this.rememberUuid(name, resolution.value(), since);
                            answers.put(key, resolution.value());
                        }));
                    }
                    return CompletableFuture.allOf(remote.toArray(CompletableFuture[]::new));
                })
                .thenApply(done -> inOrder(request, answers, keyOfValid));
    }

    /** Asks {@code sources} from {@code index} on, one at a time, until one knows the answer. */
    private <V> CompletableFuture<Resolution<V>> ask(List<NameSource> sources, int index,
                                                     Function<NameSource, CompletableFuture<Optional<V>>> query) {
        if (index >= sources.size()) {
            return CompletableFuture.completedFuture(Resolution.unknown());
        }
        NameSource source = sources.get(index);
        return this.guard(source.id(), () -> query.apply(source), Optional.<V>empty()).thenCompose(answer -> answer
                .map(value -> CompletableFuture.completedFuture(new Resolution<>(Optional.of(value), source.id())))
                .orElseGet(() -> this.ask(sources, index + 1, query)));
    }

    /** Runs one source call, turning a throw, a failed future or a null into {@code unknown}. */
    private <T> CompletableFuture<T> guard(SourceId source, Supplier<CompletableFuture<T>> call, T unknown) {
        CompletableFuture<T> future;
        try {
            future = call.get();
        } catch (RuntimeException ex) {
            this.log.log(Level.FINE, "Lookup in " + source + " failed; treating it as unknown", ex);
            return CompletableFuture.completedFuture(unknown);
        }
        if (future == null) {
            return CompletableFuture.completedFuture(unknown);
        }
        return future.handle((value, ex) -> {
            if (ex != null) {
                this.log.log(Level.FINE, "Lookup in " + source + " failed; treating it as unknown", ex);
                return unknown;
            }
            return value == null ? unknown : value;
        });
    }

    /**
     * Moves completion onto the executor and replaces any failure with {@code unknown}, which is
     * what makes the API's "never completes exceptionally, completes on Namebook's executor" true.
     */
    private <T> CompletableFuture<T> finish(Supplier<CompletableFuture<T>> work, T unknown) {
        CompletableFuture<T> future;
        try {
            future = work.get();
        } catch (RuntimeException ex) {
            future = CompletableFuture.failedFuture(ex);
        }
        return future.handleAsync((value, ex) -> {
            if (ex != null) {
                this.log.log(Level.FINE, "Lookup failed; answering unknown", ex);
                return unknown;
            }
            return value;
        }, this::execute);
    }

    /** A rejected task (the executor is shutting down) runs inline so its future still completes normally. */
    private void execute(Runnable task) {
        try {
            this.executor.execute(task);
        } catch (RejectedExecutionException ex) {
            task.run();
        }
    }

    /** {@code since} is the cache stamp from when the lookup began, so a join written meanwhile wins. */
    private void rememberName(UUID id, Optional<String> name, long since) {
        name.ifPresentOrElse(found -> this.cache.put(id, found, since), () -> this.cache.putMissingName(id, since));
    }

    private void rememberUuid(String name, Optional<UUID> id, long since) {
        id.ifPresentOrElse(found -> this.cache.putUuid(name, found, since),
                () -> this.cache.putMissingUuid(name, since));
    }

    private static <V> Resolution<V> fromCache(Optional<V> cached) {
        return cached.isPresent() ? new Resolution<>(cached, SourceId.CACHE) : Resolution.unknown();
    }

    /** Builds the result in request order; {@code keyOf} maps a requested item to its answer key, or null for none. */
    private static <K, A, V> Map<K, Optional<V>> inOrder(List<K> request, Map<A, Optional<V>> answers,
                                                        Function<K, @Nullable A> keyOf) {
        Map<K, Optional<V>> result = new LinkedHashMap<>();
        for (K item : request) {
            A key = item == null ? null : keyOf.apply(item);
            result.put(item, key == null ? Optional.empty() : answers.getOrDefault(key, Optional.empty()));
        }
        return result;
    }

    private static <K, V> Map<K, Optional<V>> unknownFor(List<K> request) {
        Map<K, Optional<V>> result = new LinkedHashMap<>();
        for (K item : request) {
            result.put(item, Optional.empty());
        }
        return result;
    }
}
