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
}
