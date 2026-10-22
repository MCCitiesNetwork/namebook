package com.minecraftcitiesnetwork.namebook.core.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NameCacheTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return this.now; }
    }

    private final MutableClock clock = new MutableClock();
    private final NameCache cache = new NameCache(this.clock, Duration.ofMinutes(10), Duration.ofMinutes(1), 100);

    @Test
    void put_answersBothDirectionsCaseInsensitively() {
        UUID id = UUID.randomUUID();
        this.cache.put(id, "Steve");
        assertEquals(Optional.of(Optional.of("Steve")), this.cache.nameOf(id));
        assertEquals(Optional.of(Optional.of(id)), this.cache.uuidOf("sTEVE"));
    }

    @Test
    void expiry_afterTtl() {
        UUID id = UUID.randomUUID();
        this.cache.put(id, "Steve");
        this.clock.advance(Duration.ofMinutes(11));
        assertTrue(this.cache.nameOf(id).isEmpty());
        assertTrue(this.cache.uuidOf("Steve").isEmpty());
    }

    @Test
    void missExpiry_afterMissTtl() {
        UUID id = UUID.randomUUID();
        this.cache.putMissingName(id);
        this.cache.putMissingUuid("Ghost");
        assertEquals(Optional.of(Optional.empty()), this.cache.nameOf(id));
        assertEquals(Optional.of(Optional.empty()), this.cache.uuidOf("ghost"));
        this.clock.advance(Duration.ofMinutes(2));
        assertTrue(this.cache.nameOf(id).isEmpty());
        assertTrue(this.cache.uuidOf("Ghost").isEmpty());
    }

    @Test
    void put_movesNameFromPreviousOwner() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        this.cache.put(a, "Steve");
        this.cache.put(b, "steve");
        assertEquals(Optional.of(Optional.of(b)), this.cache.uuidOf("STEVE"));
        assertTrue(this.cache.nameOf(a).isEmpty());
    }

    @Test
    void evicts_oldestBeyondMax() {
        NameCache small = new NameCache(this.clock, Duration.ofMinutes(10), Duration.ofMinutes(1), 2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        small.put(a, "A");
        small.put(b, "B");
        small.put(c, "C");
        assertTrue(small.nameOf(a).isEmpty());
        assertTrue(small.uuidOf("A").isEmpty());
        assertTrue(small.nameOf(b).isPresent());
        assertTrue(small.nameOf(c).isPresent());
    }

    @Test
    void putUuid_doesNotSetName() {
        UUID id = UUID.randomUUID();
        this.cache.put(id, "Steve");
        this.cache.putUuid("steve", id);
        assertEquals(Optional.of(Optional.of("Steve")), this.cache.nameOf(id));
        assertEquals(Optional.of(Optional.of(id)), this.cache.uuidOf("STEVE"));

        UUID other = UUID.randomUUID();
        this.cache.putUuid("alex", other);
        assertTrue(this.cache.nameOf(other).isEmpty());
        assertEquals(Optional.of(Optional.of(other)), this.cache.uuidOf("Alex"));
    }

    @Test
    void putJoined_winsOverLookupStartedEarlier() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        long since = this.cache.stamp();
        this.cache.putJoined(a, "New");

        this.cache.put(a, "Old", since);
        this.cache.putUuid("Old", a, since);
        this.cache.putMissingName(a, since);
        this.cache.put(b, "new", since);
        this.cache.putMissingUuid("New", since);

        assertEquals(Optional.of(Optional.of("New")), this.cache.nameOf(a));
        assertEquals(Optional.of(Optional.of(a)), this.cache.uuidOf("new"));
        assertTrue(this.cache.uuidOf("old").isEmpty());
        assertTrue(this.cache.nameOf(b).isEmpty());
    }

    @Test
    void putJoined_lookupStartedLaterApplies() {
        UUID a = UUID.randomUUID();
        this.cache.putJoined(a, "New");
        long since = this.cache.stamp();

        this.cache.put(a, "Newer", since);

        assertEquals(Optional.of(Optional.of("Newer")), this.cache.nameOf(a));
    }

    @Test
    void putJoined_expiredJoinNoLongerBlocks() {
        UUID a = UUID.randomUUID();
        long since = this.cache.stamp();
        this.cache.putJoined(a, "New");
        this.clock.advance(Duration.ofMinutes(11));

        this.cache.put(a, "Old", since);

        assertEquals(Optional.of(Optional.of("Old")), this.cache.nameOf(a));
    }
}
