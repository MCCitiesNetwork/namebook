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

class MojangSourceTest {

    private static final UUID NOTCH = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String NOTCH_BODY = "{\"id\":\"069a79f444e94726a5befca90e38aaf5\",\"name\":\"Notch\"}";

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

    private MojangSource source(int perMinute, Duration timeout) {
        return new MojangSource(this.http, this.server.uri(), this.server.uri(), timeout,
                new RateLimiter(Clock.systemUTC(), perMinute), "Namebook/test", new NameRules("."));
    }

    private MojangSource source() {
        return this.source(60, Duration.ofSeconds(2));
    }

    @Test
    void found_byName() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.of(NOTCH), this.source().uuidOf("notch").join());
        assertEquals(List.of("/users/profiles/minecraft/notch"), this.server.paths);
    }

    @Test
    void found_byUuid() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.of("Notch"), this.source().nameOf(NOTCH).join());
        assertEquals(List.of("/session/minecraft/profile/069a79f444e94726a5befca90e38aaf5"), this.server.paths);
    }

    @Test
    void unknown_onNotFound() {
        this.server.reply(404, "{}");
        assertEquals(Optional.empty(), this.source().uuidOf("Nobody").join());
        this.server.reply(204, "");
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
        this.server.reply(200, "<html>");
        assertEquals(Optional.empty(), this.source().uuidOf("Notch").join());
        assertEquals(Optional.empty(), this.source().nameOf(NOTCH).join());
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
        MojangSource source = this.source(60, Duration.ofMillis(200));
        assertEquals(Optional.empty(), source.uuidOf("Notch").orTimeout(1, TimeUnit.SECONDS).join());
    }

    @Test
    void skips_bedrockWithoutRequest() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.empty(), this.source().uuidOf(".Major_Nelson").join());
        assertEquals(Optional.empty(), this.source().uuidOf("a b").join());
        assertTrue(this.server.paths.isEmpty());
    }

    @Test
    void skips_floodgateUuidWithoutRequest() {
        this.server.reply(200, NOTCH_BODY);
        assertEquals(Optional.empty(), this.source().nameOf(NameRules.floodgateUuid(2584878536129841L)).join());
        assertTrue(this.server.paths.isEmpty());
    }

    @Test
    void rateLimited_returnsUnknownWithoutRequest() {
        this.server.reply(200, NOTCH_BODY);
        MojangSource source = this.source(0, Duration.ofSeconds(2));
        assertEquals(Optional.empty(), source.uuidOf("Notch").join());
        assertEquals(Optional.empty(), source.nameOf(NOTCH).join());
        assertTrue(this.server.paths.isEmpty());
    }

    @Test
    void sends_userAgent() {
        this.server.reply(200, NOTCH_BODY);
        this.source().uuidOf("Notch").join();
        assertEquals(List.of("Namebook/test"), this.server.userAgents);
    }
}
