package com.minecraftcitiesnetwork.namebook.core.store;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.mariadb.MariaDBContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

abstract class AbstractDatabaseTest {

    private static final String ROOT_PASSWORD = "rootpass";
    private static final String VERSION_TABLE = "namebook_schema_version";

    // One container and one pool for every test class; both are torn down with the JVM.
    protected static final MariaDBContainer CONTAINER = new MariaDBContainer("mariadb:11.7")
            .withEnv("MARIADB_ROOT_PASSWORD", ROOT_PASSWORD);

    static {
        CONTAINER.start();
    }

    protected static NamebookDatabase database;

    /** The settings for the shared container, as the plugin would be configured. */
    protected static DatabaseSettings settings() {
        // NamebookDatabase prepends "jdbc:" itself.
        String url = CONTAINER.getJdbcUrl().substring("jdbc:".length());
        return new DatabaseSettings(url, CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    @BeforeAll
    static void openDatabase() throws SQLException {
        if (database == null) {
            database = NamebookDatabase.open(settings(), Logger.getLogger("test"));
        }
    }

    @BeforeEach
    void truncateTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(CONTAINER.getJdbcUrl(), "root", ROOT_PASSWORD);
             Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()"
                            + " AND table_name LIKE 'namebook\\_%'")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (!name.equals(VERSION_TABLE)) {
                        tables.add(name);
                    }
                }
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
