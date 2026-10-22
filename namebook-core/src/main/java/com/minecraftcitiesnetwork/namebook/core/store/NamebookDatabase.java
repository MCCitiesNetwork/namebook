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
        try {
            new NamebookMigrator().migrate(dataSource, NamebookMigrator.BASE_DIR, NamebookMigrator.STEPS,
                    NamebookDatabase.class.getClassLoader(), logger);
        } catch (SQLException | RuntimeException ex) {
            dataSource.forceCloseAll();
            throw ex;
        }
        return new NamebookDatabase(dataSource, buildSessionFactory(dataSource));
    }

    /**
     * Builds the JDBC URL, pinning the connection's time zone to the JVM's default.
     *
     * <p>Namebook stores no timestamps today. The pin is kept so that a future time column, read
     * or written through this pool, means the same instant in Java and in SQL whatever the
     * database server's own time zone is.
     *
     * <p>An explicit {@code connectionTimeZone} in the configured URL is left untouched.
     */
    private static @NotNull String jdbcUrl(@NotNull DatabaseSettings settings) {
        String url = "jdbc:" + settings.url();
        if (url.contains("connectionTimeZone=")) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=true";
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
