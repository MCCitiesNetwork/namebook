package com.minecraftcitiesnetwork.namebook.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamebookPluginTest {

    private static final Logger LOG = Logger.getLogger("test");

    @TempDir
    Path plugins;

    @Test
    void bedrockPrefix_prefersFloodgateConfig() throws Exception {
        Path floodgate = this.plugins.resolve("floodgate");
        Files.createDirectories(floodgate);
        Files.writeString(floodgate.resolve("config.yml"), "key-file-name: key.pem\nusername-prefix: '*'\n");

        NamebookPlugin.BedrockPrefix prefix = NamebookPlugin.resolveBedrockPrefix(this.plugins, ".", LOG);

        assertEquals("*", prefix.prefix());
        assertTrue(prefix.fromFloodgate());
    }

    @Test
    void bedrockPrefix_fallsBackToSetting() {
        NamebookPlugin.BedrockPrefix prefix = NamebookPlugin.resolveBedrockPrefix(this.plugins, "#", LOG);

        assertEquals("#", prefix.prefix());
        assertFalse(prefix.fromFloodgate());
    }

    @Test
    void bedrockPrefix_floodgateWithoutKey_fallsBackToSetting() throws Exception {
        Path floodgate = this.plugins.resolve("floodgate");
        Files.createDirectories(floodgate);
        Files.writeString(floodgate.resolve("config.yml"), "key-file-name: key.pem\n");

        NamebookPlugin.BedrockPrefix prefix = NamebookPlugin.resolveBedrockPrefix(this.plugins, "#", LOG);

        assertEquals("#", prefix.prefix());
        assertFalse(prefix.fromFloodgate());
    }

    @Test
    void bedrockPrefix_floodgateEmptyPrefix_isKept() throws Exception {
        Path floodgate = this.plugins.resolve("floodgate");
        Files.createDirectories(floodgate);
        Files.writeString(floodgate.resolve("config.yml"), "username-prefix: ''\n");

        NamebookPlugin.BedrockPrefix prefix = NamebookPlugin.resolveBedrockPrefix(this.plugins, ".", LOG);

        assertEquals("", prefix.prefix());
        assertTrue(prefix.fromFloodgate());
    }
}
