package com.minecraftcitiesnetwork.namebook.core.store;

import com.minecraftcitiesnetwork.namebook.core.store.mapper.PlayerMapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.jetbrains.annotations.NotNull;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads and writes the UUID to name table. Every method blocks; callers run it on an executor.
 * Names are trusted: callers validate them first.
 */
public final class PlayerStore {

    // Two joins racing for one name can hit a duplicate key or a deadlock; the loser succeeds on a retry.
    private static final int MAX_ATTEMPTS = 3;

    private final NamebookDatabase database;

    public PlayerStore(@NotNull NamebookDatabase database) {
        this.database = database;
    }

    /** Makes {@code name} belong to {@code id}, taking it from any other player; the newest join wins. */
    public void record(@NotNull UUID id, @NotNull String name) {
        for (int attempt = 1; ; attempt++) {
            try (SqlSession session = this.database.openSession()) {
                PlayerMapper mapper = session.getMapper(PlayerMapper.class);
                mapper.deleteOtherHolders(id, name);
                mapper.upsert(id, name);
                session.commit();
                return;
            } catch (PersistenceException ex) {
                if (attempt >= MAX_ATTEMPTS || !isRaceFailure(ex)) {
                    throw ex;
                }
            }
        }
    }

    public @NotNull Optional<String> nameOf(@NotNull UUID id) {
        try (SqlSession session = this.database.openSession()) {
            return Optional.ofNullable(session.getMapper(PlayerMapper.class).nameOf(id));
        }
    }

    public @NotNull Optional<UUID> uuidOf(@NotNull String name) {
        try (SqlSession session = this.database.openSession()) {
            return Optional.ofNullable(session.getMapper(PlayerMapper.class).uuidOf(name));
        }
    }

    /** One query for all ids; ids with no row are absent from the result. */
    public @NotNull Map<UUID, String> namesOf(@NotNull Collection<UUID> ids) {
        Map<UUID, String> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        try (SqlSession session = this.database.openSession()) {
            for (PlayerMapper.Row row : session.getMapper(PlayerMapper.class).namesOf(new HashSet<>(ids))) {
                result.put(row.id(), row.name());
            }
        }
        return result;
    }

    /** One query for all names; keys are lower-case, and unknown names are absent. */
    public @NotNull Map<String, UUID> uuidsOf(@NotNull Collection<String> names) {
        Map<String, UUID> result = new HashMap<>();
        if (names.isEmpty()) {
            return result;
        }
        try (SqlSession session = this.database.openSession()) {
            for (PlayerMapper.Row row : session.getMapper(PlayerMapper.class).uuidsOf(new HashSet<>(names))) {
                result.put(row.name().toLowerCase(Locale.ROOT), row.id());
            }
        }
        return result;
    }

    /** Inserts only when neither the UUID nor the name exists; returns whether a row was inserted. */
    public boolean insertIfAbsent(@NotNull UUID id, @NotNull String name) {
        try (SqlSession session = this.database.openSession()) {
            int inserted = session.getMapper(PlayerMapper.class).insertIfAbsent(id, name);
            session.commit();
            return inserted > 0;
        }
    }

    private static boolean isRaceFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                // 23000: duplicate key; 40001: deadlock or serialization failure.
                return "23000".equals(state) || "40001".equals(state);
            }
        }
        return false;
    }
}
