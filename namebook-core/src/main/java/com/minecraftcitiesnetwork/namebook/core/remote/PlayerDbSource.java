package com.minecraftcitiesnetwork.namebook.core.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.minecraftcitiesnetwork.namebook.core.limit.RateLimiter;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.source.NameSource;
import com.minecraftcitiesnetwork.namebook.core.source.SourceId;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** Looks names up on playerdb.co, which serves both Java and Xbox (Bedrock) players. */
public final class PlayerDbSource implements NameSource {

    private final HttpClient http;
    private final URI base;
    private final Duration timeout;
    private final RateLimiter limiter;
    private final String userAgent;
    private final NameRules rules;

    public PlayerDbSource(HttpClient http, URI base, Duration timeout, RateLimiter limiter, String userAgent,
            NameRules rules) {
        this.http = http;
        this.base = base;
        this.timeout = timeout;
        this.limiter = limiter;
        this.userAgent = userAgent;
        this.rules = rules;
    }

    @Override
    public SourceId id() {
        return SourceId.PLAYERDB;
    }

    @Override
    public CompletableFuture<Optional<String>> nameOf(UUID id) {
        boolean floodgate = NameRules.isFloodgate(id);
        String path = floodgate ? "/api/player/xbox/" : "/api/player/minecraft/";
        String segment = floodgate ? Long.toUnsignedString(NameRules.xuid(id)) : id.toString();
        return this.fetch(path, segment).thenApply(player -> {
            String username = HttpJson.text(player, "username");
            if (username == null) {
                return Optional.<String>empty();
            }
            if (floodgate) {
                String answerId = HttpJson.text(player, "id");
                if (!Long.toUnsignedString(NameRules.xuid(id)).equals(answerId)) {
                    return Optional.<String>empty();
                }
                return Optional.of(this.rules.bedrockName(username));
            }
            return id.equals(HttpJson.parseUuid(HttpJson.text(player, "id"))) ? Optional.of(username) : Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<UUID>> uuidOf(String name) {
        if (!this.rules.isValid(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (this.rules.isBedrock(name)) {
            // A cut-down gamertag could belong to a different, shorter Xbox account.
            if (this.rules.mayBeTruncated(name)) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            String query = this.rules.gamertagQuery(name);
            return this.fetch("/api/player/xbox/", query).thenApply(player -> {
                String username = HttpJson.text(player, "username");
                String xuidText = HttpJson.text(player, "id");
                if (username == null || xuidText == null) {
                    return Optional.<UUID>empty();
                }
                // Underscores are dropped on both sides because a gamertag cannot tell them from spaces.
                if (!this.rules.gamertagQuery(this.rules.bedrockName(username))
                        .equalsIgnoreCase(query)) {
                    return Optional.<UUID>empty();
                }
                try {
                    return Optional.of(NameRules.floodgateUuid(Long.parseUnsignedLong(xuidText)));
                } catch (NumberFormatException exception) {
                    return Optional.<UUID>empty();
                }
            });
        }
        return this.fetch("/api/player/minecraft/", name).thenApply(player -> {
            String username = HttpJson.text(player, "username");
            UUID found = HttpJson.parseUuid(HttpJson.text(player, "id"));
            if (username == null || found == null || !username.equalsIgnoreCase(name)) {
                return Optional.<UUID>empty();
            }
            return Optional.of(found);
        });
    }

    /** Returns data.player when the service reports success, or null for anything else. */
    private CompletableFuture<@Nullable JsonNode> fetch(String path, String segment) {
        if (!this.limiter.tryAcquire()) {
            return CompletableFuture.completedFuture(null);
        }
        return HttpJson.get(this.http, HttpJson.uri(this.base, path, segment), this.timeout, this.userAgent)
                .thenApply(body -> {
                    if (body.isEmpty() || !body.get().path("success").asBoolean(false)) {
                        return null;
                    }
                    JsonNode player = body.get().path("data").path("player");
                    return player.isObject() ? player : null;
                });
    }
}
