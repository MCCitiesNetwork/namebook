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
 */
public final class NameCache {

    private record Entry<T>(@Nullable T value, Instant expires) {
    }

    private final Clock clock;
    private final Duration ttl;
    private final Duration missTtl;
    private final int maxEntries;
    // Insertion-ordered so the oldest entry is the first one; put() re-inserts to keep that true.
    private final LinkedHashMap<UUID, Entry<String>> names = new LinkedHashMap<>();
    private final LinkedHashMap<String, Entry<UUID>> uuids = new LinkedHashMap<>();

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

    public synchronized void put(UUID id, String name) {
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

        this.names.put(id, new Entry<>(name, expires));
        this.uuids.put(key, new Entry<>(id, expires));
        this.evict();
    }

    public synchronized void putMissingName(UUID id) {
        this.names.remove(id);
        this.names.put(id, new Entry<>(null, this.clock.instant().plus(this.missTtl)));
        this.evict();
    }

    public synchronized void putMissingUuid(String name) {
        String key = NameRules.key(name);
        this.uuids.remove(key);
        this.uuids.put(key, new Entry<>(null, this.clock.instant().plus(this.missTtl)));
        this.evict();
    }

    public synchronized void clear() {
        this.names.clear();
        this.uuids.clear();
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
