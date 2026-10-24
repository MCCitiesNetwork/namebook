package com.minecraftcitiesnetwork.namebook.paper.source;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Answers from the server's own profile caches, through the {@link PlayerProfile} API only. These run
 * on Namebook's threads, and Paper guards both caches with locks.
 *
 * <ul>
 *   <li>UUID to name reads the user cache ({@code usercache.json}): players who joined this server, or
 *       were looked up by it, even before Namebook was installed.</li>
 *   <li>Name to UUID reads only Paper's cache of recently completed profiles, which matches the exact
 *       spelling. Players in the user cache are found through the database instead, once
 *       {@code /namebook import usercache} has run.</li>
 * </ul>
 *
 * <p>{@code OfflinePlayer} is never used. On Paper 26.3, for a player missing from the user cache,
 * {@code OfflinePlayer#getName()} loads the player's playerdata file from disk on every call, along with
 * any plugin that hooks the load (MyWorlds upgrades the whole file), and {@code getOfflinePlayerIfCached}
 * reads the online players, which only the main thread may do. Nothing here goes to the network either:
 * {@code completeFromCache(true, ...)} and {@code getOfflinePlayer(String)} may ask Mojang for an unknown
 * name, which would bypass Namebook's rate limits.
 */
public final class ServerCacheSource implements NameSource {

    private final Server server;
    private final Executor executor;

    public ServerCacheSource(@NotNull Server server, @NotNull Executor executor) {
        this.server = server;
        this.executor = executor;
    }

    @Override
    public SourceId id() {
        return SourceId.SERVER;
    }

    @Override
    public CompletableFuture<Optional<String>> nameOf(UUID id) {
        return this.read(() -> {
            PlayerProfile profile = this.server.createProfile(id);
            return profile.completeFromCache()
                    ? Optional.ofNullable(profile.getName()).filter(name -> !name.isEmpty())
                    : Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<UUID>> uuidOf(String name) {
        return this.read(() -> {
            PlayerProfile profile = this.server.createProfile(name);
            return profile.completeFromCache(false, true)
                    ? Optional.ofNullable(profile.getId())
                    : Optional.empty();
        });
    }

    /** A failure of any kind, including the executor refusing work at shutdown, counts as unknown. */
    private <T> CompletableFuture<Optional<T>> read(Supplier<Optional<T>> query) {
        try {
            return CompletableFuture.supplyAsync(query, this.executor)
                    .exceptionally(ex -> Optional.empty());
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }
}
