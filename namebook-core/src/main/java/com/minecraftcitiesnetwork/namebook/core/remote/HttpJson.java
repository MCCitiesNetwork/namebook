package com.minecraftcitiesnetwork.namebook.core.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/** Shared plumbing for the remote sources: every failure becomes an empty result instead of an exception. */
final class HttpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpJson() {
    }

    /** Fetches a 200 response as JSON. Any other status, a bad body or a network failure gives empty. */
    static CompletableFuture<Optional<JsonNode>> get(HttpClient http, URI uri, Duration timeout, String userAgent) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    // The request timeout only covers the response headers, so bound the whole exchange too.
                    .orTimeout(timeout.toMillis() + 100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .handle((response, error) -> {
                        if (error != null || response == null || response.statusCode() != 200) {
                            return Optional.<JsonNode>empty();
                        }
                        try {
                            return Optional.ofNullable(MAPPER.readTree(response.body()));
                        } catch (Exception exception) {
                            return Optional.<JsonNode>empty();
                        }
                    });
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    static URI uri(URI base, String path, String segment) {
        String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
        String root = base.toString().replaceAll("/+$", "");
        return URI.create(root + path + encoded);
    }

    static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode child = node.get(field);
        return child != null && child.isTextual() ? child.asText() : null;
    }

    static String withoutDashes(UUID id) {
        return id.toString().replace("-", "");
    }

    /** Accepts both the dashed and the 32-character form. */
    static @Nullable UUID parseUuid(@Nullable String text) {
        if (text == null) {
            return null;
        }
        try {
            if (text.length() == 32) {
                return UUID.fromString(text.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                        "$1-$2-$3-$4-$5"));
            }
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
