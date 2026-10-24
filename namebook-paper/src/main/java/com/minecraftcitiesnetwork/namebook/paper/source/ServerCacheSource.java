package com.minecraftcitiesnetwork.namebook.paper.source;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Answers from the server's own user cache ({@code usercache.json}): players who joined this
 * server, or were looked up by it, even before Namebook was installed.
 *
 * <p>Only calls that never go to the network are used. {@code getOfflinePlayer(String)} may ask Mojang
 * for an unknown name, which would bypass Namebook's rate limits, so it is never called.
 *
 * <p>A name is read through {@link PlayerProfile#completeFromCache()}, not {@code OfflinePlayer#getName()}:
 * on Paper 26.3, for a player missing from the user cache, {@code getName()} loads the player's
 * playerdata file from disk on every call, along with any plugin that hooks the load (MyWorlds
 * upgrades the whole file). {@code completeFromCache()} reads only Paper's profile caches, which Paper
 * guards with locks.
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
        return this.read(() -> Optional.ofNullable(this.server.getOfflinePlayerIfCached(name))
                .map(OfflinePlayer::getUniqueId));
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
