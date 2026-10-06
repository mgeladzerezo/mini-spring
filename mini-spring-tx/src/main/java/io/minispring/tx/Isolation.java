package io.minispring.tx;

import java.sql.Connection;

/** Transaction isolation levels, mapped onto the JDBC constants. */
public enum Isolation {
    /** Whatever the database or connection pool is configured with. */
    DEFAULT(-1),
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE);

    private final int jdbcLevel;

    Isolation(int jdbcLevel) {
        this.jdbcLevel = jdbcLevel;
    }

    /** The {@code java.sql.Connection.TRANSACTION_*} value, or -1 for {@link #DEFAULT}. */
    public int jdbcLevel() {
        return jdbcLevel;
    }
}
