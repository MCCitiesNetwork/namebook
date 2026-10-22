package com.minecraftcitiesnetwork.namebook.core.remote;

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

/** Looks Java names up on Mojang's own services. Bedrock names and Floodgate UUIDs are never sent there. */
public final class MojangSource implements NameSource {

    private final HttpClient http;
    private final URI sessionBase;
    private final URI apiBase;
    private final Duration timeout;
    private final RateLimiter limiter;
    private final String userAgent;
    private final NameRules rules;

    public MojangSource(HttpClient http, URI sessionBase, URI apiBase, Duration timeout, RateLimiter limiter,
            String userAgent, NameRules rules) {
        this.http = http;
        this.sessionBase = sessionBase;
        this.apiBase = apiBase;
        this.timeout = timeout;
        this.limiter = limiter;
        this.userAgent = userAgent;
        this.rules = rules;
    }

    @Override
    public SourceId id() {
        return SourceId.MOJANG;
    }

    @Override
    public CompletableFuture<Optional<String>> nameOf(UUID id) {
        if (NameRules.isFloodgate(id) || !this.limiter.tryAcquire()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        URI uri = HttpJson.uri(this.sessionBase, "/session/minecraft/profile/", HttpJson.withoutDashes(id));
        return HttpJson.get(this.http, uri, this.timeout, this.userAgent).thenApply(body -> {
            if (body.isEmpty()) {
                return Optional.<String>empty();
            }
            String name = HttpJson.text(body.get(), "name");
            if (name == null || !id.equals(HttpJson.parseUuid(HttpJson.text(body.get(), "id")))) {
                return Optional.<String>empty();
            }
            return Optional.of(name);
        });
    }

    @Override
    public CompletableFuture<Optional<UUID>> uuidOf(String name) {
        if (!this.rules.isValid(name) || this.rules.isBedrock(name) || !this.limiter.tryAcquire()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        URI uri = HttpJson.uri(this.apiBase, "/users/profiles/minecraft/", name);
        return HttpJson.get(this.http, uri, this.timeout, this.userAgent).thenApply(body -> {
            if (body.isEmpty()) {
                return Optional.<UUID>empty();
            }
            String answeredName = HttpJson.text(body.get(), "name");
            UUID found = HttpJson.parseUuid(HttpJson.text(body.get(), "id"));
            if (found == null || answeredName == null || !answeredName.equalsIgnoreCase(name)) {
                return Optional.<UUID>empty();
            }
            return Optional.of(found);
        });
    }
}
