package com.minecraftcitiesnetwork.namebook.core.store;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationTest extends AbstractDatabaseTest {

    private static int count(String sql) throws SQLException {
        try (Connection c = database.dataSource().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void migrate_createsTable() throws SQLException {
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
                + " AND table_name = 'namebook_players'"));
        assertTrue(count("SELECT MAX(version) FROM namebook_schema_version") >= 1);
    }

    @Test
    void migrate_isIdempotent() throws SQLException {
        int before = count("SELECT COUNT(*) FROM namebook_schema_version");
        try (NamebookDatabase second = NamebookDatabase.open(settings(), Logger.getLogger("test"))) {
            assertTrue(second.dataSource() != null);
        }
        assertEquals(before, count("SELECT COUNT(*) FROM namebook_schema_version"));
    }
}
