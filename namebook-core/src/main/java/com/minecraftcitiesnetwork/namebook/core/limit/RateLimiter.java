package com.minecraftcitiesnetwork.namebook.core.limit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/** Sliding 60-second window limiter. A limit of zero or less never allows anything. */
public final class RateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final int perMinute;
    private final ArrayDeque<Instant> grants = new ArrayDeque<>();

    public RateLimiter(Clock clock, int perMinute) {
        this.clock = clock;
        this.perMinute = perMinute;
    }

    public synchronized boolean tryAcquire() {
        if (this.perMinute <= 0) {
            return false;
        }
        Instant now = this.clock.instant();
        Instant cutoff = now.minus(WINDOW);
        while (!this.grants.isEmpty() && !this.grants.peekFirst().isAfter(cutoff)) {
            this.grants.pollFirst();
        }
        if (this.grants.size() >= this.perMinute) {
            return false;
        }
        this.grants.addLast(now);
        return true;
    }
}
