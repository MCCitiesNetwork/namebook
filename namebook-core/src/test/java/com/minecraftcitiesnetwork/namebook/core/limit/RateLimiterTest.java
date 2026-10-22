package com.minecraftcitiesnetwork.namebook.core.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration d) { this.now = this.now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return this.now; }
    }

    private final MutableClock clock = new MutableClock();

    @Test
    void tryAcquire_allowsUpToLimitPerMinute() {
        RateLimiter limiter = new RateLimiter(this.clock, 3);
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void tryAcquire_recoversAfterWindow() {
        RateLimiter limiter = new RateLimiter(this.clock, 1);
        assertTrue(limiter.tryAcquire());
        this.clock.advance(Duration.ofSeconds(59));
        assertFalse(limiter.tryAcquire());
        this.clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire());
    }

    @Test
    void zeroLimit_neverAllows() {
        assertFalse(new RateLimiter(this.clock, 0).tryAcquire());
        assertFalse(new RateLimiter(this.clock, -5).tryAcquire());
    }
}
