package com.minecraftcitiesnetwork.namebook.paper.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void load_completeFile_isLeftAsWritten() throws Exception {
        ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);
        Path live = this.dir.resolve("settings.yml");
        String edited = Files.readString(live).replace("ttl: 10m", "ttl: 20m # longer, for the event");
        Files.writeString(live, edited);

        Settings settings = ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);

        assertEquals(edited, Files.readString(live));
        assertEquals(java.time.Duration.ofMinutes(20), settings.cache().ttl());
    }

    @Test
    void load_freshCopy_keepsBundledComments() throws Exception {
        ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);
        assertEquals(Files.readString(this.dir.resolve("defaults/default-settings.yml")),
                Files.readString(this.dir.resolve("settings.yml")));
    }

    @Test
    void load_missingKey_isAddedToTheFile() throws Exception {
        Path live = this.dir.resolve("settings.yml");
        Files.writeString(live, "# mine\ncache:\n  ttl: 20m\n");

        ConfigTestSupport.loader(this.dir).load("settings.yml", Settings.class);

        String written = Files.readString(live);
        assertTrue(written.contains("replace-spaces"), written);
        assertTrue(written.contains("20m"), written);
    }
}
