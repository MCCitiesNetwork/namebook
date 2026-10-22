package com.minecraftcitiesnetwork.namebook.paper.config;

import com.minecraftcitiesnetwork.namebook.core.store.DatabaseSettings;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/** The contents of {@code database.yml}. Blank means the admin has not configured it yet. */
@ConfigSerializable
public record DatabaseConfig(String url, String username, String password) {

    public DatabaseConfig {
        url = url == null ? "" : url;
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    /** Connection details as plain values, so storage code does not depend on the YAML types. */
    public DatabaseSettings toSettings() {
        return new DatabaseSettings(this.url, this.username, this.password);
    }

    public boolean isBlank() {
        return this.url.isBlank();
    }
}
