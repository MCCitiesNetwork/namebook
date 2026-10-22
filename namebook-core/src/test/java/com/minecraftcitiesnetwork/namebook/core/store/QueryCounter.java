package com.minecraftcitiesnetwork.namebook.core.store;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts the SELECT statements one {@link NamebookDatabase} sends. Unlike the server-wide
 * {@code Com_select}, it sees only this database's own queries, so other tests running at the
 * same time cannot change the count.
 */
@Intercepts(@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}))
final class QueryCounter implements Interceptor {

    private final AtomicInteger queries = new AtomicInteger();

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        this.queries.incrementAndGet();
        return invocation.proceed();
    }

    int count() {
        return this.queries.get();
    }
}
