package com.minecraftcitiesnetwork.namebook.core.chain;

import com.minecraftcitiesnetwork.namebook.core.cache.NameCache;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.source.Resolution;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import com.minecraftcitiesnetwork.namebook.core.store.DatabaseSource;
import com.minecraftcitiesnetwork.namebook.core.store.PlayerStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LookupChainTest {

    private static final String CHAIN_THREAD = "namebook-test-chain";

    /** A remote-source stand-in that answers from maps, counts calls and records the global call order. */
    private static final class FakeSource implements NameSource {
        private final SourceId id;
        private final List<SourceId> callLog;
        private final Map<UUID, String> names = new HashMap<>();
        private final Map<String, UUID> uuids = new HashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private boolean throwing;

        FakeSource(SourceId id, List<SourceId> callLog) {
            this.id = id;
            this.callLog = callLog;
        }

        FakeSource knows(UUID id, String name) {
            this.names.put(id, name);
            this.uuids.put(NameRules.key(name), id);
            return this;
        }

        FakeSource throwing() {
            this.throwing = true;
            return this;
        }

        @Override
        public SourceId id() {
            return this.id;
        }

        @Override
        public CompletableFuture<Optional<String>> nameOf(UUID id) {
            this.calls.incrementAndGet();
            synchronized (this.callLog) {
                this.callLog.add(this.id);
            }
            if (this.throwing) {
                throw new IllegalStateException("boom");
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(this.names.get(id)));
        }

        @Override
        public CompletableFuture<Optional<UUID>> uuidOf(String name) {
            this.calls.incrementAndGet();
            synchronized (this.callLog) {
                this.callLog.add(this.id);
            }
            if (this.throwing) {
                return CompletableFuture.failedFuture(new IllegalStateException("boom"));
            }
            return CompletableFuture.completedFuture(Optional.ofNullable(this.uuids.get(NameRules.key(name))));
        }
    }

    private final List<SourceId> callLog = new ArrayList<>();
    private final NameCache cache = new NameCache(Clock.systemUTC(), Duration.ofMinutes(10), Duration.ofMinutes(1), 1000);
    private final NameRules rules = new NameRules(".", true);
    private ExecutorService chainExecutor;
    private ExecutorService databaseExecutor;
    private PlayerStore store;
    private FakeSource server;
    private FakeSource playerdb;
    private FakeSource mojang;
    private LookupChain chain;

    @BeforeEach
    void setUp() {
        this.chainExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, CHAIN_THREAD));
        this.databaseExecutor = Executors.newSingleThreadExecutor();
        this.store = mock(PlayerStore.class);
        when(this.store.nameOf(any())).thenReturn(Optional.empty());
        when(this.store.uuidOf(any())).thenReturn(Optional.empty());
        when(this.store.namesOf(anyCollection())).thenReturn(Map.of());
        when(this.store.uuidsOf(anyCollection())).thenReturn(Map.of());
        this.server = new FakeSource(SourceId.SERVER, this.callLog);
        this.playerdb = new FakeSource(SourceId.PLAYERDB, this.callLog);
        this.mojang = new FakeSource(SourceId.MOJANG, this.callLog);
        Logger log = Logger.getLogger("LookupChainTest");
        DatabaseSource database = new DatabaseSource(this.store, this.databaseExecutor, log);
        this.chain = new LookupChain(this.rules, this.cache, database,
                List.of(this.server, this.playerdb, this.mojang), this.chainExecutor, log);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        this.chainExecutor.shutdownNow();
        this.databaseExecutor.shutdownNow();
        this.chainExecutor.awaitTermination(5, TimeUnit.SECONDS);
        this.databaseExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static <T> T join(CompletableFuture<T> future) {
        return future.orTimeout(5, TimeUnit.SECONDS).join();
    }

    @Test
    void order_cacheThenDatabaseThenFallbacks() {
        UUID id = UUID.randomUUID();
        this.mojang.knows(id, "Steve");

        assertEquals(Optional.of("Steve"), join(this.chain.nameOf(id)));

        verify(this.store).nameOf(id);
        assertEquals(List.of(SourceId.SERVER, SourceId.PLAYERDB, SourceId.MOJANG), this.callLog);

        // The answer is cached now, so nothing is asked a second time.
        assertEquals(Optional.of("Steve"), join(this.chain.nameOf(id)));
        verify(this.store, times(1)).nameOf(id);
        assertEquals(3, this.callLog.size());
    }

    @Test
    void stops_atFirstAnswer() {
        UUID id = UUID.randomUUID();
        when(this.store.uuidOf("Steve")).thenReturn(Optional.of(id));

        assertEquals(Optional.of(id), join(this.chain.uuidOf("Steve")));

        assertEquals(0, this.server.calls.get());
        assertEquals(0, this.playerdb.calls.get());
        assertEquals(0, this.mojang.calls.get());
    }

    @Test
    void playerdb_beforeMojang() {
        UUID id = UUID.randomUUID();
        this.playerdb.knows(id, "Steve");
        this.mojang.knows(id, "Steve");

        assertEquals(Optional.of(id), join(this.chain.uuidOf("Steve")));

        assertEquals(List.of(SourceId.SERVER, SourceId.PLAYERDB), this.callLog);
        assertEquals(0, this.mojang.calls.get());
    }

    @Test
    void miss_isCachedAndSkipsSourcesNextTime() {
        UUID id = UUID.randomUUID();

        assertEquals(Optional.empty(), join(this.chain.nameOf(id)));
        assertEquals(Optional.empty(), join(this.chain.nameOf(id)));
        assertEquals(Optional.empty(), join(this.chain.uuidOf("Nobody")));
        assertEquals(Optional.empty(), join(this.chain.uuidOf("NOBODY")));

        verify(this.store, times(1)).nameOf(id);
        verify(this.store, times(1)).uuidOf(any());
        assertEquals(2, this.server.calls.get());
        assertEquals(2, this.mojang.calls.get());
    }

    @Test
    void invalidName_touchesNothing() {
        List<String> bad = List.of("%", "a b", "a".repeat(40), "", ".", "Steve%");
        for (String name : bad) {
            assertEquals(Optional.empty(), join(this.chain.uuidOf(name)), name);
        }
        Map<String, Optional<UUID>> bulk = join(this.chain.uuidsOf(bad));
        assertEquals(bad, List.copyOf(bulk.keySet()));
        assertTrue(bulk.values().stream().allMatch(Optional::isEmpty));

        verify(this.store, never()).uuidOf(any());
        verify(this.store, never()).uuidsOf(anyCollection());
        assertTrue(this.callLog.isEmpty());
    }

    @Test
    void failingSource_treatedAsUnknown() {
        UUID id = UUID.randomUUID();
        when(this.store.nameOf(id)).thenThrow(new IllegalStateException("database down"));
        this.server.knows(id, "Steve");

        Resolution<String> resolution = join(this.chain.resolveName(id));

        assertEquals(Optional.of("Steve"), resolution.value());
        assertEquals(SourceId.SERVER, resolution.source());
    }

    @Test
    void neverCompletesExceptionally() {
        UUID id = UUID.randomUUID();
        when(this.store.nameOf(any())).thenThrow(new IllegalStateException("database down"));
        when(this.store.uuidOf(any())).thenThrow(new IllegalStateException("database down"));
        when(this.store.namesOf(anyCollection())).thenThrow(new IllegalStateException("database down"));
        when(this.store.uuidsOf(anyCollection())).thenThrow(new IllegalStateException("database down"));
        this.server.throwing();
        this.playerdb.throwing();
        this.mojang.throwing();

        assertEquals(Optional.empty(), join(this.chain.nameOf(id)));
        assertEquals(Optional.empty(), join(this.chain.uuidOf("Steve")));
        assertEquals(Map.of(id, Optional.empty()), join(this.chain.namesOf(List.of(id))));
        assertEquals(Map.of("Alex", Optional.empty()), join(this.chain.uuidsOf(List.of("Alex"))));
        assertEquals(Optional.empty(), join(this.chain.nameOf(null)));
        assertEquals(Optional.empty(), join(this.chain.uuidOf(null)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulk_duplicatesAndCase_oneDatabaseCall() {
        UUID id = UUID.randomUUID();
        when(this.store.uuidsOf(anyCollection())).thenReturn(Map.of("steve", id));

        Map<String, Optional<UUID>> result = join(this.chain.uuidsOf(List.of("Steve", "steve", "STEVE")));

        assertEquals(List.of("Steve", "steve", "STEVE"), List.copyOf(result.keySet()));
        assertTrue(result.values().stream().allMatch(Optional.of(id)::equals));
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(this.store, times(1)).uuidsOf(asked.capture());
        assertEquals(1, asked.getValue().size());
        verify(this.store, never()).uuidOf(any());
        assertTrue(this.callLog.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulk_keepsRequestOrder() {
        UUID cached = UUID.randomUUID();
        UUID inDatabase = UUID.randomUUID();
        UUID onServer = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        this.cache.put(cached, "Cached");
        when(this.store.namesOf(anyCollection())).thenReturn(Map.of(inDatabase, "Stored"));
        this.server.knows(onServer, "Online");

        List<UUID> request = List.of(unknown, onServer, cached, inDatabase, onServer);
        Map<UUID, Optional<String>> result = join(this.chain.namesOf(request));

        assertEquals(List.of(unknown, onServer, cached, inDatabase), List.copyOf(result.keySet()));
        assertEquals(Optional.empty(), result.get(unknown));
        assertEquals(Optional.of("Online"), result.get(onServer));
        assertEquals(Optional.of("Cached"), result.get(cached));
        assertEquals(Optional.of("Stored"), result.get(inDatabase));
        ArgumentCaptor<Collection<UUID>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(this.store, times(1)).namesOf(asked.capture());
        assertEquals(3, asked.getValue().size());
        verify(this.store, never()).nameOf(any());

        Map<String, Optional<UUID>> names = join(this.chain.uuidsOf(List.of("online", "Cached", "zzz")));
        assertEquals(List.of("online", "Cached", "zzz"), List.copyOf(names.keySet()));
        assertEquals(Optional.of(onServer), names.get("online"));
        assertEquals(Optional.of(cached), names.get("Cached"));
        assertEquals(Optional.empty(), names.get("zzz"));
    }

    @Test
    void resolve_reportsSource() {
        UUID stored = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        when(this.store.nameOf(stored)).thenReturn(Optional.of("Stored"));
        this.playerdb.knows(remote, "Remote");

        assertEquals(new Resolution<>(Optional.of("Stored"), SourceId.DATABASE), join(this.chain.resolveName(stored)));
        assertEquals(new Resolution<>(Optional.of("Stored"), SourceId.CACHE), join(this.chain.resolveName(stored)));
        assertEquals(new Resolution<>(Optional.of(remote), SourceId.PLAYERDB), join(this.chain.resolveUuid("Remote")));

        Resolution<UUID> missing = join(this.chain.resolveUuid("Nobody"));
        assertEquals(Optional.empty(), missing.value());
        assertNull(missing.source());
    }

    @Test
    void futures_completeOnChainExecutor() {
        UUID id = UUID.randomUUID();
        UUID remote = UUID.randomUUID();
        this.cache.put(id, "Steve");
        this.server.knows(remote, "Alex");

        // Hold the chain's only thread so neither future can finish before the probes are attached.
        CountDownLatch release = new CountDownLatch(1);
        this.chainExecutor.execute(() -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<String> cachedThread = this.chain.nameOf(id).thenApply(ignored -> Thread.currentThread().getName());
        CompletableFuture<String> remoteThread = this.chain.uuidOf("Alex").thenApply(ignored -> Thread.currentThread().getName());
        release.countDown();

        assertEquals(CHAIN_THREAD, join(cachedThread));
        assertEquals(CHAIN_THREAD, join(remoteThread));
    }

    @Test
    void recordLocally_answersFromCacheWithoutSources() {
        UUID id = UUID.randomUUID();
        this.chain.recordLocally(id, "Steve");

        assertEquals(new Resolution<>(Optional.of(id), SourceId.CACHE), join(this.chain.resolveUuid("steve")));
        verify(this.store, never()).uuidOf(any());
        assertTrue(this.callLog.isEmpty());
    }

    @Test
    void uuidLookup_doesNotChangeCachedCapitalisation() {
        UUID id = UUID.randomUUID();
        this.server.knows(id, "Steve");
        when(this.store.nameOf(id)).thenReturn(Optional.of("Steve"));

        assertEquals(Optional.of(id), join(this.chain.uuidOf("steve")));

        // The lookup cached no name, so the database is asked and its spelling wins.
        assertEquals(Optional.of("Steve"), join(this.chain.nameOf(id)));
        verify(this.store).nameOf(id);
    }
}
