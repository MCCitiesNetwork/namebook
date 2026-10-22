package com.minecraftcitiesnetwork.namebook.core.store;

import com.minecraftcitiesnetwork.pluginInfrastructure.sql.MigrationStep;
import com.minecraftcitiesnetwork.pluginInfrastructure.sql.SchemaMigrator;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

/** Applies Namebook's schema migrations, tracking progress in {@code namebook_schema_version}. */
public final class NamebookMigrator extends SchemaMigrator {

    public static final List<MigrationStep> STEPS = List.of(
            new MigrationStep(1, "players", "V1__players.sql"));

    public static final Path BASE_DIR = Path.of("sql/migrations");

    @Override
    protected @NotNull String bootstrapDdl() {
        return "CREATE TABLE IF NOT EXISTS namebook_schema_version ("
                + "version INT PRIMARY KEY, "
                + "description VARCHAR(255), "
                + "applied_at DATETIME DEFAULT NOW())";
    }

    @Override
    protected @NotNull String selectVersion() {
        return "SELECT COALESCE(MAX(version), 0) FROM namebook_schema_version";
    }

    @Override
    protected @NotNull String insertVersion() {
        return "INSERT INTO namebook_schema_version (version, description) VALUES (?, ?)";
    }
}
