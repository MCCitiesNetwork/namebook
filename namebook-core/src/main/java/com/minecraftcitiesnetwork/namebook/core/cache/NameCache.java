package com.minecraftcitiesnetwork.namebook.core.cache;

import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * In-memory UUID/name cache with separate lifetimes for hits and misses. Outer empty means "not
 * cached"; inner empty means "cached miss". Names are matched case-insensitively.
 *
 * <p>A name written by a join ({@link #putJoined}) is the truth until it expires. A lookup result
 * carries the {@link #stamp()} taken when the lookup started, and is dropped if a join for the same
 * player or name was written after that: the lookup may have read the player's old name.
 */
public final class NameCache {

    /** Lookup results without a stamp always apply. */
    private static final long ALWAYS = Long.MAX_VALUE;
    /** The join stamp of an entry no join wrote. */
    private static final long NOT_JOINED = Long.MIN_VALUE;

    private record Entry<T>(@Nullable T value, Instant expires, long joined) {
        Entry(@Nullable T value, Instant expires) {
            this(value, expires, NOT_JOINED);
        }
    }

    private final Clock clock;
    private final Duration ttl;
    private final Duration missTtl;
    private final int maxEntries;
    // Insertion-ordered so the oldest entry is the first one; put() re-inserts to keep that true.
    private final LinkedHashMap<UUID, Entry<String>> names = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry<UUID>> uuids = new LinkedHashMap<>();
    /** Counts join writes; a lookup started at stamp {@code s} loses to any join with a higher number. */
    private long joins;

    public NameCache(Clock clock, Duration ttl, Duration missTtl, int maxEntries) {
        this.clock = clock;
        this.ttl = ttl;
        this.missTtl = missTtl;
        this.maxEntries = maxEntries;
    }

    public synchronized Optional<Optional<String>> nameOf(UUID id) {
        return this.read(this.names, id);
    }

    public synchronized Optional<Optional<UUID>> uuidOf(String name) {
        return this.read(this.uuids, NameRules.key(name));
    }

    /** The current join count, taken when a lookup starts and passed back with its result. */
    public synchronized long stamp() {
        return this.joins;
    }

    /** Caches a name a player joined with. Lookups that started before this cannot replace it. */
    public synchronized void putJoined(UUID id, String name) {
        this.joins++;
        this.write(id, name, this.joins);
    }

    public synchronized void put(UUID id, String name) {
        this.put(id, name, ALWAYS);
    }

    /** Caches a lookup result, unless a join for this player or name came after {@code since}. */
    public synchronized void put(UUID id, String name, long since) {
        if (this.joinedAfter(id, since) || this.joinedAfter(NameRules.key(name), since)) {
            return;
        }
        this.write(id, name, NOT_JOINED);
    }

    private void write(UUID id, String name, long joined) {
        String key = NameRules.key(name);
        Instant expires = this.clock.instant().plus(this.ttl);

        // Drop the old name this player had, so it no longer points at them.
        Entry<String> previousName = this.names.remove(id);
        if (previousName != null && previousName.value() != null) {
            String oldKey = NameRules.key(previousName.value());
            Entry<UUID> oldClaim = this.uuids.get(oldKey);
            if (oldClaim != null && id.equals(oldClaim.value())) {
                this.uuids.remove(oldKey);
            }
        }
        // The newest owner of a name wins; the previous owner's cached name is no longer true.
        Entry<UUID> previousOwner = this.uuids.remove(key);
        if (previousOwner != null && previousOwner.value() != null && !previousOwner.value().equals(id)) {
            this.names.remove(previousOwner.value());
        }

        this.names.put(id, new Entry<>(name, expires, joined));
        this.uuids.put(key, new Entry<>(id, expires, joined));
        this.evict();
    }

    /**
     * Caches only name to UUID. A lookup by name carries the caller's spelling, which must not
     * replace the player's own capitalisation in the UUID to name direction.
     */
    public synchronized void putUuid(String name, UUID id) {
        this.putUuid(name, id, ALWAYS);
    }

    /** Like {@link #putUuid(String, UUID)}, unless a join for this player or name came after {@code since}. */
    public synchronized void putUuid(String name, UUID id, long since) {
        String key = NameRules.key(name);
        if (this.joinedAfter(id, since) || this.joinedAfter(key, since)) {
            return;
        }
        // The newest owner of a name wins; drop the previous owner's cached name if it is this one.
        Entry<UUID> previousOwner = this.uuids.remove(key);
        if (previousOwner != null && previousOwner.value() != null && !previousOwner.value().equals(id)) {
            Entry<String> theirName = this.names.get(previousOwner.value());
            if (theirName != null && theirName.value() != null && NameRules.key(theirName.value()).equals(key)) {
                this.names.remove(previousOwner.value());
            }
        }
        this.uuids.put(key, new Entry<>(id, this.clock.instant().plus(this.ttl)));
        this.evict();
    }

    public synchronized void putMissingName(UUID id) {
        this.putMissingName(id, ALWAYS);
    }

    public synchronized void putMissingName(UUID id, long since) {
        if (this.joinedAfter(id, since)) {
            return;
        }
        this.names.remove(id);
        this.names.put(id, new Entry<>(null, this.clock.instant().plus(this.missTtl)));
        this.evict();
    }

    public synchronized void putMissingUuid(String name) {
        this.putMissingUuid(name, ALWAYS);
    }

    public synchronized void putMissingUuid(String name, long since) {
        String key = NameRules.key(name);
        if (this.joinedAfter(key, since)) {
            return;
        }
        this.uuids.remove(key);
        this.uuids.put(key, new Entry<>(null, this.clock.instant().plus(this.missTtl)));
        this.evict();
    }

    public synchronized void clear() {
        this.names.clear();
        this.uuids.clear();
    }

    private boolean joinedAfter(UUID id, long since) {
        return this.joinedAfter(this.names, id, since);
    }

    private boolean joinedAfter(String key, long since) {
        return this.joinedAfter(this.uuids, key, since);
    }

    /** True when {@code key} holds a live entry a join wrote after the lookup stamped {@code since} began. */
    private <K> boolean joinedAfter(Map<K, ? extends Entry<?>> map, K key, long since) {
        Entry<?> entry = map.get(key);
        return entry != null && entry.joined() > since && entry.expires().isAfter(this.clock.instant());
    }

    private <K, V> Optional<Optional<V>> read(Map<K, Entry<V>> map, K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!entry.expires().isAfter(this.clock.instant())) {
            map.remove(key);
            return Optional.empty();
        }
        return Optional.of(Optional.ofNullable(entry.value()));
    }

    private void evict() {
        this.trim(this.names);
        this.trim(this.uuids);
    }

    private void trim(Map<?, ?> map) {
        Iterator<?> oldest = map.keySet().iterator();
        while (map.size() > this.maxEntries && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }
}
