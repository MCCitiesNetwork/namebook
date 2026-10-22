package com.minecraftcitiesnetwork.namebook.paper.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesConfigTest {

    @TempDir
    Path dir;

    @Test
    void messages_everyKeyExists() throws Exception {
        Messages messages = ConfigTestSupport.loader(this.dir).loadMessages();
        int checked = 0;
        for (Field f : MessageKeys.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) {
                continue;
            }
            String key = (String) f.get(null);
            String raw = messages.miniMessageFormattedFor(key);
            assertNotEquals(key, raw, "missing message for " + key);
            assertFalse(raw.isBlank(), "empty message for " + key);
            checked++;
        }
        assertTrue(checked == 7);
    }
}
