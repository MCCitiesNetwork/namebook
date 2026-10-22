package com.minecraftcitiesnetwork.namebook.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minecraftcitiesnetwork.namebook.core.limit.RateLimiter;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerDbSourceTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String NOTCH_BODY = found("Notch", "069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID NELSON = UUID.fromString("00000000-0000-0000-0009-2eeeeeeefd31");
    private static final String NELSON_BODY = found("Major Nelson", "2584878536129841");

    private final NameRules rules = new NameRules(".", true);
    private final HttpClient http = HttpClient.newHttpClient();
    private StubServer server;

    @BeforeEach
    void start() throws Exception {
        this.server = new StubServer();
    }

    @AfterEach
    void stop() {
        this.server.close();
    }

    private static String found(String username, String id) {
        return "{\"success\":true,\"code\":\"player.found\",\"data\":{\"player\":{\"username\":\"" + username
                + "\",\"id\":\"" + id + "\"}}}";
    }

    private PlayerDbSource source(int perMinute, Duration timeout) {
        return new PlayerDbSource(this.http, this.server.uri(), timeout,
                new RateLimiter(Clock.systemUTC(), perMinute), "Namebook/test", this.rules);
    }

    private PlayerDbSource source() {
        return this.source(60, Duration.ofSeconds(2));
    }

    @Test
    void found_byName() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.of(NOTCH), this.source().uuidOf("notch").join());
        assertEquals(List.of("/api/player/minecraft/notch"), this.server.paths);
    }

    @Test
    void found_byUuid() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.of("Notch"), this.source().nameOf(NOTCH).join());
        assertEquals(List.of("/api/player/minecraft/" + NOTCH), this.server.paths);
    }

    @Test
    void bedrock_byFloodgateUuid() {
        this.server.reply(200, NELSON_BODY);
        assertEquals(Optional.of(".Major_Nelson"), this.source().nameOf(NELSON).join());
        assertEquals(List.of("/api/player/xbox/2584878536129841"), this.server.paths);
    }

    @Test
    void bedrock_byName() {
        this.server.reply(200, NELSON_BODY);
        assertEquals(Optional.of(NELSON), this.source().uuidOf(".Major_Nelson").join());
        assertEquals(List.of("/api/player/xbox/MajorNelson"), this.server.paths);
    }

    @Test
    void bedrock_mismatchIsUnknown() {
        this.server.reply(200, found("Someone Else", "2584878536129841"));
        assertEquals(Optional.empty(), this.source().uuidOf(".Major_Nelson").join());
        this.server.reply(200, found("Major Nelson", "1111"));
        assertEquals(Optional.empty(), this.source().nameOf(NELSON).join());
    }

    @Test
    void unknown_onNotFound() {
        this.server.reply(200, "{\"success\":false,\"code\":\"minecraft.invalid_username\"}");
        assertEquals(Optional.empty(), this.source().uuidOf("Nobody").join());
        this.server.reply(404, "{}");
        assertEquals(Optional.empty(), this.source().nameOf(NOTCH).join());
    }

    @Test
    void unknown_on500() {
        this.server.reply(500, "oops");
        assertEquals(Optional.empty(), this.source().uuidOf("Notch").join());
        this.server.reply(429, "slow down");
        assertEquals(Optional.empty(), this.source().nameOf(NOTCH).join());
    }

    @Test
    void unknown_onMalformedJson() {
        this.server.reply(200, "{not json");
        assertEquals(Optional.empty(), this.source().uuidOf("Notch").join());
        this.server.reply(200, "{\"success\":true,\"data\":{\"player\":{\"username\":5}}}");
        assertEquals(Optional.empty(), this.source().uuidOf("Notch").join());
    }

    @Test
    void unknown_onMismatchedAnswer() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.empty(), this.source().uuidOf("Jeb_").join());
        assertEquals(Optional.empty(), this.source().nameOf(UUID.randomUUID()).join());
    }

    @Test
    void unknown_onTimeout() {
        this.server.reply(200, NOTCH_BODY).delay(1500);
        PlayerDbSource source = this.source(60, Duration.ofMillis(200));
        assertEquals(Optional.empty(), source.uuidOf("Notch").orTimeout(1, TimeUnit.SECONDS).join());
    }

    @Test
    void skips_bedrockWithoutRequest_forInvalidNames() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.empty(), this.source().uuidOf("a b").join());
        assertEquals(Optional.empty(), this.source().uuidOf("%").join());
        assertTrue(this.server.paths.isEmpty());
    }

    @Test
    void rateLimited_returnsUnknownWithoutRequest() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.empty(), this.source(0, Duration.ofSeconds(2)).uuidOf("Notch").join());
        assertTrue(this.server.paths.isEmpty());
    }

    @Test
    void sends_userAgent() {
        this.server.reply(200, NOTCH_BODY);
        this.source().uuidOf("Notch").join();
        assertEquals(List.of("Namebook/test"), this.server.userAgents);
    }
}
