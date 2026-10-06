package io.minispring.tx;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The thread-bound state behind declarative transactions: for each {@link DataSource}, the
 * connection of the transaction running on this thread, if any.
 *
 * <p>A {@code ThreadLocal} is the right tool here, not a smell: a transaction belongs to the
 * thread executing the call, and virtual threads (used by the web module) each get their own
 * value. The consequence, which the README states, is that work handed to another thread does
 * not take part in the caller's transaction.
 */
public final class TransactionContext {

    private static final ThreadLocal<Map<DataSource, ConnectionHolder>> BOUND =
            ThreadLocal.withInitial(IdentityHashMap::new);

    private TransactionContext() {
    }

    /** Whether a transaction is running on this thread for the data source. */
    public static boolean isActive(DataSource dataSource) {
        return holder(dataSource) != null;
    }

    /** Name of the running transaction (its definition's name), or {@code null} if none. */
    public static String currentTransactionName(DataSource dataSource) {
        ConnectionHolder holder = holder(dataSource);
        return holder == null ? null : holder.owner();
    }

    /**
     * The connection to use for data access: the transaction's own if one is running, otherwise a
     * fresh auto-commit connection. Pair every call with {@link #release}.
     */
    public static Connection connection(DataSource dataSource) throws SQLException {
        ConnectionHolder holder = holder(dataSource);
        return holder != null ? holder.connection() : dataSource.getConnection();
    }

    /** Closes the connection unless it belongs to the running transaction. */
    public static void release(Connection connection, DataSource dataSource) {
        ConnectionHolder holder = holder(dataSource);
        if (holder != null && holder.connection() == connection) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // nothing useful can be done about a failing close of a connection we are discarding
        }
    }

    static ConnectionHolder holder(DataSource dataSource) {
        return BOUND.get().get(dataSource);
    }

    static void bind(DataSource dataSource, ConnectionHolder holder) {
        BOUND.get().put(dataSource, holder);
    }

    static ConnectionHolder unbind(DataSource dataSource) {
        Map<DataSource, ConnectionHolder> map = BOUND.get();
        ConnectionHolder removed = map.remove(dataSource);
        if (map.isEmpty()) {
            BOUND.remove();
        }
        return removed;
    }
}
