package io.minispring.tx;

import java.sql.Connection;

/**
 * The connection bound to the current thread for the duration of a transaction, plus the flag
 * every participant shares: once any joined method decides to roll back, nobody may commit.
 */
final class ConnectionHolder {

    private final Connection connection;
    private final String owner;
    private boolean rollbackOnly;

    ConnectionHolder(Connection connection, String owner) {
        this.connection = connection;
        this.owner = owner;
    }

    Connection connection() {
        return connection;
    }

    /** Name of the definition that started this transaction. */
    String owner() {
        return owner;
    }

    boolean isRollbackOnly() {
        return rollbackOnly;
    }

    void markRollbackOnly() {
        rollbackOnly = true;
    }
}
