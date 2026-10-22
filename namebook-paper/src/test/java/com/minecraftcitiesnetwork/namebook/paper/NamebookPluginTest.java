package com.minecraftcitiesnetwork.namebook.paper;

import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.paper.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamebookPluginTest {

    private static final Logger LOG = Logger.getLogger("test");
    private static final Settings.Bedrock DOT = new Settings.Bedrock(".", true);

    @TempDir
    Path plugins;

    private void floodgateConfig(String yaml) throws Exception {
        Path floodgate = this.plugins.resolve("floodgate");
        Files.createDirectories(floodgate);
        Files.writeString(floodgate.resolve("config.yml"), yaml);
    }

    @Test
    void bedrockNames_prefersFloodgateConfig() throws Exception {
        this.floodgateConfig("key-file-name: key.pem\nusername-prefix: '*'\nreplace-spaces: true\n");

        NamebookPlugin.BedrockNames names = NamebookPlugin.resolveBedrockNames(this.plugins, DOT, LOG);

        assertEquals("*", names.prefix());
        assertTrue(names.replaceSpaces());
        assertTrue(names.fromFloodgate());
    }

    @Test
    void bedrockNames_readsFloodgateReplaceSpaces() throws Exception {
        this.floodgateConfig("username-prefix: '.'\nreplace-spaces: false\n");

        NamebookPlugin.BedrockNames names = NamebookPlugin.resolveBedrockNames(this.plugins, DOT, LOG);

        assertFalse(names.replaceSpaces());
        assertTrue(names.fromFloodgate());
    }

    @Test
    void bedrockNames_floodgateWithoutReplaceSpaces_usesSetting() throws Exception {
        this.floodgateConfig("username-prefix: '*'\n");

        NamebookPlugin.BedrockNames names =
                NamebookPlugin.resolveBedrockNames(this.plugins, new Settings.Bedrock(".", false), LOG);

        assertEquals("*", names.prefix());
        assertFalse(names.replaceSpaces());
    }

    @Test
    void bedrockNames_fallsBackToSetting() {
        NamebookPlugin.BedrockNames names =
                NamebookPlugin.resolveBedrockNames(this.plugins, new Settings.Bedrock("#", false), LOG);

        assertEquals("#", names.prefix());
        assertFalse(names.replaceSpaces());
        assertFalse(names.fromFloodgate());
    }

    @Test
    void bedrockNames_floodgateWithoutKey_fallsBackToSetting() throws Exception {
        this.floodgateConfig("key-file-name: key.pem\n");

        NamebookPlugin.BedrockNames names =
                NamebookPlugin.resolveBedrockNames(this.plugins, new Settings.Bedrock("#", true), LOG);

        assertEquals("#", names.prefix());
        assertFalse(names.fromFloodgate());
    }

    @Test
    void bedrockNames_floodgateEmptyPrefix_isKept() throws Exception {
        this.floodgateConfig("username-prefix: ''\n");

        NamebookPlugin.BedrockNames names = NamebookPlugin.resolveBedrockNames(this.plugins, DOT, LOG);

        assertEquals("", names.prefix());
        assertTrue(names.fromFloodgate());
    }

    @Test
    void warnIfAmbiguous_warnsForNameCharacterPrefix() {
        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getAnonymousLogger();
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        NamebookPlugin.warnIfAmbiguous(new NameRules(".", true), log);
        assertTrue(records.isEmpty());

        NamebookPlugin.warnIfAmbiguous(new NameRules("_", true), log);
        assertEquals(1, records.size());
        assertEquals(Level.WARNING, records.getFirst().getLevel());
    }
}
