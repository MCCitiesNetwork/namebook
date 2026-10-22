package com.minecraftcitiesnetwork.namebook.paper.config;

import com.minecraftcitiesnetwork.namebook.core.store.DatabaseSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsConfigTest {

    @TempDir
    Path dir;

    @Test
    void settings_bundledLoadsDefaults() throws Exception {
        Settings s = ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);
        assertEquals(".", s.bedrock().prefix());
        assertEquals(Duration.ofMinutes(10), s.cache().ttl());
        assertEquals(Duration.ofMinutes(1), s.cache().missTtl());
        assertEquals(10000, s.cache().maxEntries());
        for (Settings.Remote r : new Settings.Remote[] {s.playerdb(), s.mojang()}) {
            assertTrue(r.enabled());
            assertEquals(Duration.ofSeconds(3), r.timeout());
            assertEquals(30, r.maxPerMinute());
        }
        assertTrue(Files.exists(this.dir.resolve("defaults/default-settings.yml")));
    }

    @Test
    void settings_invalidValuesFallBack() throws Exception {
        Files.writeString(this.dir.resolve("settings.yml"),
                "cache:\n  max-entries: -1\nplayerdb:\n  timeout: 0s\nmojang:\n  max-per-minute: 0\n");
        Settings s = ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);
        assertEquals(10000, s.cache().maxEntries());
        assertEquals(Duration.ofSeconds(3), s.playerdb().timeout());
        assertEquals(30, s.mojang().maxPerMinute());
    }

    @Test
    void settings_emptyBedrockPrefixIsKept() throws Exception {
        Files.writeString(this.dir.resolve("settings.yml"), "bedrock:\n  prefix: ''\n");
        Settings s = ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);
        assertEquals("", s.bedrock().prefix());
    }

    @Test
    void settings_garbageDurationFallsBack() throws Exception {
        Files.writeString(this.dir.resolve("settings.yml"), "cache:\n  ttl: soon\n");
        Settings s = ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);
        assertEquals(Duration.ofMinutes(10), s.cache().ttl());
    }

    @Test
    void database_bundledIsBlank() throws Exception {
        DatabaseConfig db = ConfigTestSupport.loader(this.dir).load("database.yml", DatabaseConfig.class);
        assertTrue(db.isBlank());
    }

    @Test
    void database_toSettingsCopiesValues() {
        DatabaseSettings settings = new DatabaseConfig("mariadb://db:3306/namebook", "user", "secret").toSettings();
        assertEquals(new DatabaseSettings("mariadb://db:3306/namebook", "user", "secret"), settings);
    }
}
