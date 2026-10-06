package io.minispring.tx;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/** A fresh in-memory H2 database per call, with a {@code log(id, tag)} table, and a way to look inside it. */
final class H2Support {

    private static final AtomicInteger COUNTER = new AtomicInteger();

    private H2Support() {
    }

    static DataSource newDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:tx" + COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=2000");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table log (id bigint auto_increment primary key, tag varchar(100) not null)");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return dataSource;
    }

    /** The committed tags, read on a connection of its own so that uncommitted rows stay invisible. */
    static List<String> committedTags(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select tag from log order by id")) {
            List<String> tags = new ArrayList<>();
            while (rows.next()) {
                tags.add(rows.getString(1));
            }
            return tags;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Wraps a data source so that the state-changing calls on its connections are appended to
     * {@code calls}, e.g. {@code setReadOnly(true)}, {@code commit}, {@code close}.
     */
    static DataSource recording(DataSource delegate, List<String> calls) {
        java.util.Set<String> recorded = java.util.Set.of("setReadOnly", "setTransactionIsolation", "setAutoCommit",
                "commit", "rollback", "close");
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(H2Support.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) {
                        return invoke(method, delegate, args);
                    }
                    Connection real = delegate.getConnection();
                    return java.lang.reflect.Proxy.newProxyInstance(H2Support.class.getClassLoader(),
                            new Class<?>[] {Connection.class}, (connection, call, callArgs) -> {
                                if (recorded.contains(call.getName())) {
                                    calls.add(call.getName()
                                            + (callArgs == null ? "" : "(" + callArgs[0] + ")"));
                                }
                                return invoke(call, real, callArgs);
                            });
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
