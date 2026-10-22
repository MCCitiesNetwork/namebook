package com.minecraftcitiesnetwork.namebook.core.store;

import com.minecraftcitiesnetwork.namebook.core.store.mapper.PlayerMapper;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.session.SqlSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * Callers validate names first; the store only refuses a name too long for its column, so it is
 * never cut short.
 */
public final class PlayerStore {

    // Two joins racing for one name can hit a duplicate key or a deadlock; the loser succeeds on a retry.
    private static final int MAX_ATTEMPTS = 3;
    /** The width of {@code namebook_players.name}. */
    static final int MAX_NAME_LENGTH = 32;

    private final NamebookDatabase database;

    public PlayerStore(@NotNull NamebookDatabase database) {
        this.database = database;
    }

    /** Makes {@code name} belong to {@code id}, taking it from any other player; the newest join wins. */
    public void record(@NotNull UUID id, @NotNull String name) {
        checkLength(name);
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
        checkLength(name);
        for (int attempt = 1; ; attempt++) {
            try (SqlSession session = this.database.openSession()) {
                int inserted = session.getMapper(PlayerMapper.class).insertIfAbsent(id, name);
                session.commit();
                return inserted > 0;
            } catch (PersistenceException ex) {
                String state = sqlState(ex);
                // Another writer inserted the same UUID or name after the existence check.
                if ("23000".equals(state)) {
                    return false;
                }
                if (attempt >= MAX_ATTEMPTS || !"40001".equals(state)) {
                    throw ex;
                }
            }
        }
    }

    private static void checkLength(String name) {
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Name is longer than " + MAX_NAME_LENGTH + " characters: " + name);
        }
    }

    private static boolean isRaceFailure(Throwable ex) {
        String state = sqlState(ex);
        // 23000: duplicate key; 40001: deadlock or serialization failure.
        return "23000".equals(state) || "40001".equals(state);
    }

    private static @Nullable String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
