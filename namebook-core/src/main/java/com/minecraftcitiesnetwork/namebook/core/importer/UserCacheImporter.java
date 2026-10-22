package com.minecraftcitiesnetwork.namebook.core.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minecraftcitiesnetwork.namebook.core.name.NameRules;
import com.minecraftcitiesnetwork.namebook.core.store.PlayerStore;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Seeds the database from the server's usercache.json without ever changing a row that exists. */
public final class UserCacheImporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlayerStore store;
    private final NameRules rules;

    public UserCacheImporter(@NotNull PlayerStore store, @NotNull NameRules rules) {
        this.store = store;
        this.rules = rules;
    }

    /** Blocks. Throws when the file cannot be read or is not JSON; bad entries are skipped and counted. */
    public @NotNull ImportResult importFile(@NotNull Path usercacheJson) throws IOException {
        JsonNode root;
        try (InputStream in = Files.newInputStream(usercacheJson)) {
            root = MAPPER.readTree(in);
        }
        if (root == null || !root.isArray()) {
            throw new IOException("usercache.json is not a JSON array");
        }
        int added = 0;
        int skipped = 0;
        for (JsonNode entry : root) {
            UUID id = parseUuid(entry.path("uuid").asText(null));
            String name = entry.path("name").isTextual() ? entry.path("name").asText() : null;
            if (id == null || !this.rules.isValid(name) || !this.store.insertIfAbsent(id, name)) {
                skipped++;
            } else {
                added++;
            }
        }
        return new ImportResult(added, skipped);
    }

    private static UUID parseUuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
