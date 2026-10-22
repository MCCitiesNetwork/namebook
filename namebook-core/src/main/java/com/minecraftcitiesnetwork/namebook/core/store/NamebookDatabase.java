package com.minecraftcitiesnetwork.namebook.core.store;

import com.minecraftcitiesnetwork.namebook.core.store.mapper.PlayerMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

/** A pooled MariaDB connection source with Namebook's schema applied and MyBatis configured. */
public final class NamebookDatabase implements AutoCloseable {

    private final PooledDataSource dataSource;
    private final SqlSessionFactory sessionFactory;

    private NamebookDatabase(@NotNull PooledDataSource dataSource, @NotNull SqlSessionFactory sessionFactory) {
        this.dataSource = dataSource;
        this.sessionFactory = sessionFactory;
    }

    /**
     * Builds the connection pool and brings the schema up to date.
     *
     * @throws SQLException if the database is unreachable, a migration fails, or the schema is newer
     *                      than this plugin understands. The pool is closed before the exception leaves.
     */
    public static @NotNull NamebookDatabase open(@NotNull DatabaseSettings settings, @NotNull Logger logger)
            throws SQLException {
        PooledDataSource dataSource = new PooledDataSource(
                "org.mariadb.jdbc.Driver", jdbcUrl(settings), settings.username(), settings.password());
        dataSource.setPoolPingEnabled(true);
        dataSource.setPoolPingQuery("SELECT 1");
        dataSource.setPoolPingConnectionsNotUsedFor(600000);
        // Everything after the pool exists is inside the try, so no failure leaves it open.
        try {
            new NamebookMigrator().migrate(dataSource, NamebookMigrator.BASE_DIR, NamebookMigrator.STEPS,
                    NamebookDatabase.class.getClassLoader(), logger);
            return new NamebookDatabase(dataSource, buildSessionFactory(dataSource));
        } catch (SQLException | RuntimeException ex) {
            dataSource.forceCloseAll();
            throw ex;
        }
    }

    /**
     * Builds the JDBC URL, adding the settings below unless the configured URL already sets them.
     *
     * <p>{@code connectTimeout} and {@code socketTimeout}: by default MariaDB waits 30 seconds to
     * connect and forever on a read. A database host that vanishes would then hold pool threads on a
     * dead socket, and lookups would never fall through to the other sources.
     *
     * <p>{@code connectionTimeZone}: Namebook stores no timestamps today. The pin is kept so that a
     * future time column means the same instant in Java and in SQL whatever the database server's
     * own time zone is.
     */
    static @NotNull String jdbcUrl(@NotNull DatabaseSettings settings) {
        StringBuilder url = new StringBuilder("jdbc:").append(settings.url());
        appendUnlessSet(url, "connectionTimeZone", "connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=true");
        appendUnlessSet(url, "connectTimeout", "connectTimeout=5000");
        appendUnlessSet(url, "socketTimeout", "socketTimeout=10000");
        return url.toString();
    }

    private static void appendUnlessSet(StringBuilder url, String parameter, String setting) {
        int query = url.indexOf("?");
        if (query >= 0) {
            for (String pair : url.substring(query + 1).split("&")) {
                if (pair.startsWith(parameter + "=")) {
                    return;
                }
            }
        }
        url.append(query >= 0 ? '&' : '?').append(setting);
    }

    private static @NotNull SqlSessionFactory buildSessionFactory(@NotNull DataSource dataSource) {
        Environment environment = new Environment("production", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, UuidBinaryTypeHandler.class);
        configuration.addMapper(PlayerMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    /** Opens a session with auto-commit off; the caller commits and closes it. */
    public @NotNull SqlSession openSession() {
        return this.sessionFactory.openSession();
    }

    /**
     * Lets tests observe the statements MyBatis sends. MyBatis applies interceptors to each new
     * session, so one added after the factory is built still takes effect.
     */
    void addInterceptor(@NotNull Interceptor interceptor) {
        this.sessionFactory.getConfiguration().addInterceptor(interceptor);
    }

    public @NotNull DataSource dataSource() {
        return this.dataSource;
    }

    @Override
    public void close() {
        this.dataSource.forceCloseAll();
    }
}
