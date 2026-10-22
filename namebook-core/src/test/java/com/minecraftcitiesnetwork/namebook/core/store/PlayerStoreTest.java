package com.minecraftcitiesnetwork.namebook.core.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerStoreTest extends AbstractDatabaseTest {

    private PlayerStore store;

    @BeforeEach
    void createStore() {
        this.store = new PlayerStore(database);
    }

    private static int rowsNamed(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(CONTAINER.getJdbcUrl(), "root", "rootpass");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM namebook_players WHERE name = '" + name + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void record_insertsAndUpdates() {
        UUID id = UUID.randomUUID();
        this.store.record(id, "Steve");
        assertEquals(Optional.of("Steve"), this.store.nameOf(id));
        this.store.record(id, "Alex");
        assertEquals(Optional.of("Alex"), this.store.nameOf(id));
        assertEquals(Optional.empty(), this.store.uuidOf("Steve"));
    }

    @Test
    void record_takesNameFromOtherPlayer() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        this.store.record(b, "Steve");
        this.store.record(a, "steve");
        assertEquals(Optional.of(a), this.store.uuidOf("Steve"));
        assertEquals(Optional.empty(), this.store.nameOf(b));
    }

    @Test
    void record_concurrentSwapNeverFails() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            String name = "x" + round;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> fa = pool.submit(() -> {
                    start.await();
                    this.store.record(a, name);
                    return null;
                });
                Future<?> fb = pool.submit(() -> {
                    start.await();
                    this.store.record(b, name);
                    return null;
                });
                start.countDown();
                fa.get(30, TimeUnit.SECONDS);
                fb.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, rowsNamed(name));
        }
    }

    @Test
    void uuidOf_isCaseInsensitive() {
        UUID id = UUID.randomUUID();
        this.store.record(id, "Steve");
        assertEquals(Optional.of(id), this.store.uuidOf("sTEVE"));
    }

    @Test
    void uuidOf_underscoreIsLiteral() {
        UUID id = UUID.randomUUID();
        this.store.record(id, "a_b");
        assertEquals(Optional.of(id), this.store.uuidOf("a_b"));
        assertEquals(Optional.empty(), this.store.uuidOf("aXb"));
    }

    @Test
    void uniqueKey_rejectsCaseOnlyDuplicateViaInsertIfAbsent() {
        UUID a = UUID.randomUUID();
        assertTrue(this.store.insertIfAbsent(a, "Steve"));
        assertFalse(this.store.insertIfAbsent(UUID.randomUUID(), "STEVE"));
        assertEquals(Optional.of(a), this.store.uuidOf("steve"));
    }

    @Test
    void namesOf_oneQueryForMany() throws SQLException {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        this.store.record(a, "One");
        this.store.record(b, "Two");
        QueryCounter counter = new QueryCounter();
        try (NamebookDatabase counted = NamebookDatabase.open(settings(), Logger.getLogger("test"))) {
            counted.addInterceptor(counter);
            PlayerStore countedStore = new PlayerStore(counted);
            Map<UUID, String> names = countedStore.namesOf(List.of(a, b, c));
            assertEquals(1, counter.count());
            assertEquals(Map.of(a, "One", b, "Two"), names);
            Map<String, UUID> ids = countedStore.uuidsOf(List.of("ONE", "two", "missing"));
            assertEquals(2, counter.count());
            assertEquals(Map.of("one", a, "two", b), ids);
        }
    }

    @Test
    void insertIfAbsent_neverOverwrites() {
        UUID a = UUID.randomUUID();
        assertTrue(this.store.insertIfAbsent(a, "Steve"));
        assertFalse(this.store.insertIfAbsent(a, "Alex"));
        assertEquals(Optional.of("Steve"), this.store.nameOf(a));
    }
}
